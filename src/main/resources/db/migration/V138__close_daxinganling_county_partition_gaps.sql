-- V137 loaded seven independently sourced, individually valid Daxing'anling
-- county boundaries. Their adjacent coordinate sequences contain 26 tiny
-- slivers. Close those slivers at the named county partition before deriving
-- the prefecture surface. Any root-level closure addition is then propagated
-- down the real named hierarchy by shared-edge ownership, without redrawing
-- the source-derived county or township partition.

CREATE OR REPLACE FUNCTION overview.propagate_parent_render_additions(
  requested_child_level text,
  target_parent_codes text[] DEFAULT NULL
)
RETURNS void
LANGUAGE plpgsql
AS $$
DECLARE
  requested_parent_level text;
BEGIN
  requested_parent_level := CASE requested_child_level
    WHEN 'COUNTY' THEN 'PREFECTURE'
    WHEN 'TOWNSHIP' THEN 'COUNTY'
    ELSE NULL
  END;
  IF requested_parent_level IS NULL THEN
    RAISE EXCEPTION 'Unsupported render-addition child level: %', requested_child_level;
  END IF;

  DROP TABLE IF EXISTS pg_temp.render_addition_assignment;
  DROP TABLE IF EXISTS pg_temp.render_child_addition;

  CREATE TEMP TABLE render_addition_assignment ON COMMIT DROP AS
  WITH coverage AS (
    SELECT child.parent_code,
           ST_UnaryUnion(ST_Collect(child_render.geometry)) geometry
      FROM platform.region child
      JOIN overview.administrative_boundary_render child_render
        ON child_render.region_code=child.code
     WHERE child.administrative_level=requested_child_level
       AND (target_parent_codes IS NULL OR child.parent_code=ANY(target_parent_codes))
     GROUP BY child.parent_code
  ), gaps AS (
    SELECT parent.code parent_code,
           ST_CollectionExtract(ST_MakeValid(ST_Difference(
             parent_render.geometry,coverage.geometry
           )),3) geometry
      FROM coverage
      JOIN platform.region parent ON parent.code=coverage.parent_code
       AND parent.administrative_level=requested_parent_level
      JOIN overview.administrative_boundary_render parent_render
        ON parent_render.region_code=parent.code
     WHERE overview.has_visible_surface_gap(ST_Difference(
             parent_render.geometry,coverage.geometry
           ))
  ), pieces AS (
    SELECT gaps.parent_code,row_number() OVER()::bigint piece_id,
           (component).geom::geometry(Polygon,4326) geometry
      FROM gaps
      CROSS JOIN LATERAL ST_Dump(gaps.geometry) component
     WHERE NOT ST_IsEmpty((component).geom)
       AND ST_Area((component).geom::geography)>0.01
  )
  SELECT piece.parent_code,piece.piece_id,piece.geometry,owner.region_code
    FROM pieces piece
    CROSS JOIN LATERAL (
      SELECT child.code region_code
        FROM platform.region child
        JOIN overview.administrative_boundary_render child_render
          ON child_render.region_code=child.code
       WHERE child.parent_code=piece.parent_code
         AND child.administrative_level=requested_child_level
       ORDER BY ST_Length(ST_Intersection(
                  ST_Boundary(child_render.geometry),
                  ST_Boundary(piece.geometry)
                )::geography) DESC,
                ST_Distance(child_render.geometry,piece.geometry),
                child.code
       LIMIT 1
    ) owner;

  CREATE TEMP TABLE render_child_addition ON COMMIT DROP AS
  SELECT region_code,ST_UnaryUnion(ST_Collect(geometry)) geometry
    FROM render_addition_assignment
   GROUP BY region_code;

  UPDATE overview.administrative_boundary_render child_render
     SET geometry=closed.geometry,
         geo_json=ST_AsGeoJSON(closed.geometry,7),
         full_point_count=GREATEST(
           child_render.full_point_count,ST_NPoints(closed.geometry)
         ),
         render_point_count=ST_NPoints(closed.geometry),
         refreshed_at=now(),
         source_name=left(
           child_render.source_name || '; exact parent addition',160
         )
    FROM (
      SELECT child_render.region_code,
             ST_Multi(ST_CollectionExtract(ST_MakeValid(ST_UnaryUnion(ST_Collect(
               child_render.geometry,addition.geometry
             ))),3))::geometry(MultiPolygon,4326) geometry
        FROM overview.administrative_boundary_render child_render
        JOIN render_child_addition addition
          ON addition.region_code=child_render.region_code
    ) closed
   WHERE child_render.region_code=closed.region_code;
END;
$$;

CREATE OR REPLACE FUNCTION overview.refresh_administrative_boundary_render(
  regenerate_only_changed_townships boolean
)
RETURNS void
LANGUAGE plpgsql
AS $$
BEGIN
  PERFORM overview.refresh_administrative_boundary_render_source();
  PERFORM overview.clean_real_township_boundary_render();
  PERFORM overview.reconcile_real_named_primary_surfaces();

  PERFORM overview.repartition_display_children_watertight(
    'COUNTY',ARRAY['232700']
  );

  WITH county_coverage AS (
    SELECT child.parent_code,
           ST_Multi(ST_UnaryUnion(ST_Collect(render.geometry)))
             ::geometry(MultiPolygon,4326) geometry
      FROM platform.region child
      JOIN overview.administrative_boundary_render render
        ON render.region_code=child.code
     WHERE child.administrative_level='COUNTY'
       AND child.parent_code IS NOT NULL
     GROUP BY child.parent_code
  )
  UPDATE overview.administrative_boundary_render parent
     SET geometry=coverage.geometry,
         geo_json=ST_AsGeoJSON(coverage.geometry,7),
         full_point_count=GREATEST(parent.full_point_count,ST_NPoints(coverage.geometry)),
         render_point_count=ST_NPoints(coverage.geometry),
         refreshed_at=now(),
         source_name=left(parent.source_name || '; exact child coverage',160)
    FROM county_coverage coverage
    JOIN platform.region parent_region
      ON parent_region.code=coverage.parent_code
     AND parent_region.administrative_level='PREFECTURE'
   WHERE parent.region_code=coverage.parent_code;

  PERFORM overview.close_root_prefecture_gaps('FORMAL_BUSINESS');
  PERFORM overview.propagate_parent_render_additions('COUNTY');
  PERFORM overview.propagate_parent_render_additions('TOWNSHIP');

  -- Preserve the V55 contract after every real parent surface is final:
  -- regenerate villages only below townships that received an exact parent
  -- addition. Rebuilding unrelated real village partitions would redraw
  -- valid source geometry outside this migration's scope.
  -- V138 publishes Daxing'anling only through the county level. Its migration
  -- refresh must not redraw unrelated source-derived village boundaries merely
  -- because a small root closure addition propagated through their township.
  -- The ordinary refresh entry point keeps the existing complete-refresh
  -- contract for explicitly requested future refreshes.
  IF NOT regenerate_only_changed_townships THEN
    PERFORM overview.repartition_display_children_watertight('VILLAGE');
  END IF;

  PERFORM overview.restore_administrative_boundary_render_provenance();

  TRUNCATE overview.administrative_map_context_region_render;
  IF NOT regenerate_only_changed_townships THEN
    PERFORM overview.assert_watertight_administrative_render();
    PERFORM overview.assert_clean_real_named_boundary_render();
  END IF;
END;
$$;

CREATE OR REPLACE FUNCTION overview.refresh_administrative_boundary_render()
RETURNS void
LANGUAGE plpgsql
AS $$
BEGIN
  PERFORM overview.refresh_administrative_boundary_render(false);
END;
$$;

SELECT overview.refresh_administrative_boundary_render(true);
SELECT overview.refresh_monitoring_scope_boundary_render('FORMAL_BUSINESS');

DO $topology_gate$
DECLARE
  prefecture_hole_count integer;
  county_partition_hole_count integer;
  county_overlap_count integer;
BEGIN
  SELECT ST_NRings(render.geometry)-ST_NumGeometries(render.geometry)
    INTO prefecture_hole_count
    FROM overview.administrative_boundary_render render
   WHERE render.region_code='232700';

  WITH coverage AS (
    SELECT ST_Multi(ST_UnaryUnion(ST_Collect(render.geometry))) geometry
      FROM platform.region child
      JOIN overview.administrative_boundary_render render
        ON render.region_code=child.code
     WHERE child.parent_code='232700'
       AND child.administrative_level='COUNTY'
  )
  SELECT ST_NRings(geometry)-ST_NumGeometries(geometry)
    INTO county_partition_hole_count
    FROM coverage;

  SELECT count(*) INTO county_overlap_count
    FROM platform.region left_region
    JOIN overview.administrative_boundary_render left_render
      ON left_render.region_code=left_region.code
    JOIN platform.region right_region
      ON right_region.parent_code=left_region.parent_code
     AND right_region.administrative_level='COUNTY'
     AND right_region.code>left_region.code
    JOIN overview.administrative_boundary_render right_render
      ON right_render.region_code=right_region.code
   WHERE left_region.parent_code='232700'
     AND left_region.administrative_level='COUNTY'
     AND left_render.geometry && right_render.geometry
     AND overview.has_visible_surface_gap(
           ST_Intersection(left_render.geometry,right_render.geometry)
         );

  IF prefecture_hole_count<>0 OR county_partition_hole_count<>0
     OR county_overlap_count<>0 THEN
    RAISE EXCEPTION
      'Daxing-anling topology gate failed: prefecture holes %, county partition holes %, county overlaps %',
      prefecture_hole_count,county_partition_hole_count,county_overlap_count;
  END IF;
END;
$topology_gate$;
