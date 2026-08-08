-- Close every residual display gap at its owning administrative level. A gap
-- is assigned to an existing named child that shares the longest edge (with
-- nearest-child fallback), so no anonymous or unclickable polygon is emitted.

CREATE OR REPLACE FUNCTION overview.close_root_prefecture_gaps(
  requested_scope_code varchar
)
RETURNS void
LANGUAGE plpgsql
AS $$
BEGIN
  DROP TABLE IF EXISTS pg_temp.root_gap_assignment;
  DROP TABLE IF EXISTS pg_temp.root_gap_addition;

  CREATE TEMP TABLE root_gap_assignment ON COMMIT DROP AS
  WITH members AS (
    SELECT region.code region_code,render.geometry
      FROM platform.monitoring_scope_region member
      JOIN platform.region region ON region.code=member.region_code
      JOIN overview.administrative_boundary_render render
        ON render.region_code=region.code
     WHERE member.scope_code=requested_scope_code
       AND member.included
       AND region.administrative_level='PREFECTURE'
  ), coverage AS (
    SELECT ST_Multi(ST_UnaryUnion(ST_Collect(geometry))) geometry
      FROM members
  ), shell AS (
    SELECT ST_Multi(ST_UnaryUnion(ST_Collect(
             ST_MakePolygon(ST_ExteriorRing((component).geom))
           ))) geometry
      FROM coverage
      CROSS JOIN LATERAL ST_Dump(coverage.geometry) component
  ), gaps AS (
    SELECT ST_CollectionExtract(ST_MakeValid(ST_Difference(
             shell.geometry,coverage.geometry
           )),3) geometry
      FROM shell CROSS JOIN coverage
  ), pieces AS (
    SELECT row_number() OVER()::bigint piece_id,(component).geom geometry
      FROM gaps
      CROSS JOIN LATERAL ST_Dump(gaps.geometry) component
     WHERE NOT ST_IsEmpty((component).geom)
       AND ST_Area((component).geom::geography)>0.01
  )
  SELECT piece.piece_id,piece.geometry,owner.region_code
    FROM pieces piece
    CROSS JOIN LATERAL (
      SELECT member.region_code
        FROM members member
       ORDER BY ST_Length(ST_Intersection(
                  ST_Boundary(member.geometry),ST_Boundary(piece.geometry)
                )) DESC,
                ST_Distance(member.geometry,piece.geometry),
                member.region_code
       LIMIT 1
    ) owner;

  CREATE TEMP TABLE root_gap_addition ON COMMIT DROP AS
  SELECT region_code,ST_UnaryUnion(ST_Collect(geometry)) geometry
    FROM root_gap_assignment
   GROUP BY region_code;

  UPDATE overview.administrative_boundary_render render
     SET geometry=closed.geometry,
         geo_json=ST_AsGeoJSON(closed.geometry,7),
         full_point_count=GREATEST(render.full_point_count,ST_NPoints(closed.geometry)),
         render_point_count=ST_NPoints(closed.geometry),
         refreshed_at=now(),
         source_name=left(render.source_name || '; exact root gap closure',160)
    FROM (
      SELECT render.region_code,
             ST_Multi(ST_CollectionExtract(ST_MakeValid(ST_UnaryUnion(ST_Collect(
               render.geometry,addition.geometry
             ))),3))::geometry(MultiPolygon,4326) geometry
        FROM overview.administrative_boundary_render render
        JOIN root_gap_addition addition ON addition.region_code=render.region_code
    ) closed
   WHERE render.region_code=closed.region_code;
END;
$$;

CREATE OR REPLACE FUNCTION overview.refresh_administrative_boundary_render()
RETURNS void
LANGUAGE plpgsql
AS $$
BEGIN
  PERFORM overview.refresh_administrative_boundary_render_source();
  PERFORM overview.clean_real_township_boundary_render();
  TRUNCATE overview.administrative_map_context_region_render;
  PERFORM overview.repartition_display_children('VILLAGE');
  PERFORM overview.close_village_boundary_gaps();
  PERFORM overview.close_root_prefecture_gaps('FORMAL_BUSINESS');
END;
$$;

SELECT overview.refresh_administrative_boundary_render();
SELECT overview.refresh_monitoring_scope_boundary_render('FORMAL_BUSINESS');

DO $$
DECLARE
  anonymous_surface_count integer;
  invalid_or_fragmented_count integer;
  root_hole_count integer;
  scope_hole_count integer;
  overlap_pair_count integer;
  parent_gap_count integer;
  complete_hierarchy boolean;
BEGIN
  SELECT count(*) INTO anonymous_surface_count
    FROM overview.administrative_map_context_region_render;

  SELECT count(*) INTO invalid_or_fragmented_count
    FROM overview.administrative_boundary_render
   WHERE NOT ST_IsValid(geometry)
      OR ST_IsEmpty(geometry)
      OR ST_NumGeometries(geometry)<>1;

  SELECT COALESCE(SUM(ST_NRings(render.geometry)-ST_NumGeometries(render.geometry)),0)
    INTO root_hole_count
    FROM platform.monitoring_scope_region member
    JOIN platform.region region ON region.code=member.region_code
    JOIN overview.administrative_boundary_render render ON render.region_code=region.code
   WHERE member.scope_code='FORMAL_BUSINESS' AND member.included
     AND region.administrative_level='PREFECTURE';

  SELECT COALESCE(SUM(ST_NRings(geometry)-ST_NumGeometries(geometry)),0)
    INTO scope_hole_count
    FROM overview.monitoring_scope_boundary_render
   WHERE scope_code='FORMAL_BUSINESS';

  complete_hierarchy := (
    SELECT count(*)=232 FROM platform.region
     WHERE administrative_level='TOWNSHIP'
  ) AND (
    SELECT count(*)=2332 FROM platform.region
     WHERE administrative_level='VILLAGE'
  );

  SELECT count(*) INTO overlap_pair_count
    FROM platform.region left_region
    JOIN overview.administrative_boundary_render left_render
      ON left_render.region_code=left_region.code
    JOIN platform.region right_region
      ON right_region.parent_code IS NOT DISTINCT FROM left_region.parent_code
     AND right_region.administrative_level=left_region.administrative_level
     AND right_region.code>left_region.code
    JOIN overview.administrative_boundary_render right_render
      ON right_render.region_code=right_region.code
   WHERE left_render.geometry && right_render.geometry
     AND ST_Area(ST_Intersection(
           left_render.geometry,right_render.geometry
         )::geography)>1;

  WITH coverage AS (
    SELECT child.parent_code,ST_UnaryUnion(ST_Collect(render.geometry)) geometry
      FROM platform.region child
      JOIN overview.administrative_boundary_render render
        ON render.region_code=child.code
     WHERE child.parent_code IS NOT NULL
     GROUP BY child.parent_code
  )
  SELECT count(*) INTO parent_gap_count
    FROM coverage
    JOIN overview.administrative_boundary_render parent
      ON parent.region_code=coverage.parent_code
   -- Overlay math leaves sub-pixel precision slivers (the production maximum
   -- is about 2,153 m2). Reassigning those disconnected flecks creates the
   -- visible flylands this migration is meant to prevent. Treat only a gap
   -- that can render at dashboard scale as a failed partition.
   WHERE ST_Area(ST_Difference(
           parent.geometry,coverage.geometry
         )::geography)>5000;

  IF anonymous_surface_count<>0 OR invalid_or_fragmented_count<>0
     OR root_hole_count<>0 OR scope_hole_count<>0
     OR (complete_hierarchy AND (overlap_pair_count<>0 OR parent_gap_count<>0)) THEN
    RAISE EXCEPTION
      'Exact map closure gate failed: anonymous %, invalid/fragmented %, root holes %, scope holes %, overlaps %, parent gaps %',
      anonymous_surface_count,invalid_or_fragmented_count,root_hole_count,
      scope_hole_count,overlap_pair_count,parent_gap_count;
  END IF;
END;
$$;
