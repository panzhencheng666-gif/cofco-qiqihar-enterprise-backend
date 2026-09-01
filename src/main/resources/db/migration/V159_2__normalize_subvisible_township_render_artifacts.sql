CREATE OR REPLACE FUNCTION overview.normalize_subvisible_township_render_artifacts()
RETURNS integer
LANGUAGE plpgsql
AS $$
DECLARE
  repaired_count integer;
BEGIN
  WITH ranked_parts AS (
    SELECT render.region_code,(part).geom geometry,
           row_number() OVER (
             PARTITION BY render.region_code
             ORDER BY ST_Area((part).geom::geography) DESC
           ) component_rank,
           ST_Area(render.geometry::geography)
             - max(ST_Area((part).geom::geography)) OVER (
               PARTITION BY render.region_code
             ) discarded_area_m2
      FROM overview.administrative_boundary_render render
      JOIN platform.region region ON region.code=render.region_code
      CROSS JOIN LATERAL ST_Dump(render.geometry) part
     WHERE region.administrative_level='TOWNSHIP'
       AND ST_NumGeometries(render.geometry)>1
  ), largest_parts AS (
    SELECT region_code,ST_Multi(geometry)::geometry(MultiPolygon,4326) geometry
      FROM ranked_parts
     WHERE component_rank=1 AND discarded_area_m2<1000
  ), closed_rings AS (
    SELECT render.region_code,
           ST_Multi(ST_MakePolygon(ST_ExteriorRing(
             ST_GeometryN(render.geometry,1)
           )))::geometry(MultiPolygon,4326) geometry
      FROM overview.administrative_boundary_render render
      JOIN platform.region region ON region.code=render.region_code
     WHERE region.administrative_level='TOWNSHIP'
       AND ST_NumGeometries(render.geometry)=1
       AND ST_NumInteriorRings(ST_GeometryN(render.geometry,1))>0
       AND NOT overview.has_visible_surface_gap(ST_Difference(
         ST_MakePolygon(ST_ExteriorRing(ST_GeometryN(render.geometry,1))),
         render.geometry
       ))
  ), candidates AS (
    SELECT region_code,geometry FROM largest_parts
    UNION ALL
    SELECT region_code,geometry FROM closed_rings
  ), repaired AS (
    UPDATE overview.administrative_boundary_render render
       SET geometry=candidate.geometry,
           geo_json=ST_AsGeoJSON(candidate.geometry,7),
           full_point_count=GREATEST(
             render.full_point_count,ST_NPoints(candidate.geometry)
           ),
           render_point_count=ST_NPoints(candidate.geometry),
           refreshed_at=now(),
           source_name=left(
             render.source_name || '; normalized subvisible artifacts',160
           )
      FROM candidates candidate
     WHERE render.region_code=candidate.region_code
    RETURNING render.region_code
  )
  SELECT count(*) INTO repaired_count FROM repaired;

  RETURN repaired_count;
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

  IF NOT regenerate_only_changed_townships THEN
    PERFORM overview.normalize_subvisible_township_render_artifacts();
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

COMMENT ON FUNCTION overview.normalize_subvisible_township_render_artifacts() IS
  'Closes non-visible rings and drops sub-1000-square-metre secondary components before village display repartitioning.';
