-- Publish one continuous, named surface for every real county and township.
-- The primary source outline is retained; only detached source remnants and
-- interior rings are suppressed after adjacency reconciliation.

CREATE OR REPLACE FUNCTION overview.assert_clean_real_named_boundary_render()
RETURNS void
LANGUAGE plpgsql
AS $$
DECLARE
  malformed_real_surface_count integer;
  real_parent_gap_count integer;
  real_sibling_overlap_count integer;
  complete_hierarchy boolean;
BEGIN
  SELECT count(*) INTO malformed_real_surface_count
    FROM platform.region region
    JOIN overview.administrative_boundary_render render
      ON render.region_code=region.code
   WHERE region.administrative_level IN ('COUNTY','TOWNSHIP')
     AND (
       NOT ST_IsValid(render.geometry)
       OR ST_IsEmpty(render.geometry)
       OR ST_NumGeometries(render.geometry)<>1
       OR ST_NumInteriorRings(ST_GeometryN(render.geometry,1))<>0
     );

  complete_hierarchy := (
    SELECT count(*)=232 FROM platform.region WHERE administrative_level='TOWNSHIP'
  ) AND (
    SELECT count(*)=2332 FROM platform.region WHERE administrative_level='VILLAGE'
  );

  IF complete_hierarchy THEN
    WITH coverage AS (
      SELECT child.parent_code,ST_UnaryUnion(ST_Collect(render.geometry)) geometry
        FROM platform.region child
        JOIN overview.administrative_boundary_render render
          ON render.region_code=child.code
       WHERE child.administrative_level IN ('COUNTY','TOWNSHIP')
       GROUP BY child.parent_code
    )
    SELECT count(*) INTO real_parent_gap_count
      FROM coverage
      JOIN overview.administrative_boundary_render parent
        ON parent.region_code=coverage.parent_code
     WHERE overview.has_visible_surface_gap(ST_Difference(parent.geometry,coverage.geometry))
        OR overview.has_visible_surface_gap(ST_Difference(coverage.geometry,parent.geometry));

    SELECT count(*) INTO real_sibling_overlap_count
      FROM platform.region left_region
      JOIN overview.administrative_boundary_render left_render
        ON left_render.region_code=left_region.code
      JOIN platform.region right_region
        ON right_region.parent_code=left_region.parent_code
       AND right_region.administrative_level=left_region.administrative_level
       AND right_region.code>left_region.code
      JOIN overview.administrative_boundary_render right_render
        ON right_render.region_code=right_region.code
     WHERE left_region.administrative_level IN ('COUNTY','TOWNSHIP')
       AND left_render.geometry && right_render.geometry
       AND overview.has_visible_surface_gap(ST_Intersection(
             left_render.geometry,right_render.geometry
           ));
  ELSE
    real_parent_gap_count := 0;
    real_sibling_overlap_count := 0;
  END IF;

  IF malformed_real_surface_count<>0 OR real_parent_gap_count<>0
     OR real_sibling_overlap_count<>0 THEN
    RAISE EXCEPTION
      'Real named boundary publication gate failed: malformed %, parent gaps %, sibling overlaps %',
      malformed_real_surface_count,real_parent_gap_count,real_sibling_overlap_count;
  END IF;
END;
$$;

CREATE OR REPLACE FUNCTION overview.refresh_administrative_boundary_render()
RETURNS void
LANGUAGE plpgsql
AS $$
BEGIN
  PERFORM overview.refresh_administrative_boundary_render_source();
  PERFORM overview.clean_real_township_boundary_render();

  -- The reconciler preserves each named source core. Select its primary outer
  -- surface for publication so no hole, residual island, or unlabelled loop
  -- can become a separate 3D wall or an unclickable map target.
  UPDATE overview.administrative_boundary_render render
     SET geometry=overview.village_display_parent_surface(render.geometry),
         geo_json=ST_AsGeoJSON(overview.village_display_parent_surface(render.geometry)),
         render_point_count=ST_NPoints(overview.village_display_parent_surface(render.geometry)),
         refreshed_at=now()
    FROM platform.region region
   WHERE region.code=render.region_code
     AND region.administrative_level IN ('COUNTY','TOWNSHIP');

  PERFORM overview.close_root_prefecture_gaps('FORMAL_BUSINESS');

  CREATE TEMP TABLE village_parent_source_geometry ON COMMIT DROP AS
    SELECT render.region_code,render.geometry,render.geo_json
      FROM overview.administrative_boundary_render render
      JOIN platform.region region ON region.code=render.region_code
     WHERE region.administrative_level='TOWNSHIP';

  PERFORM overview.repartition_display_children_watertight('VILLAGE');

  UPDATE overview.administrative_boundary_render render
     SET geometry=source.geometry,
         geo_json=source.geo_json
    FROM village_parent_source_geometry source
   WHERE render.region_code=source.region_code;

  PERFORM overview.restore_administrative_boundary_render_provenance();
  TRUNCATE overview.administrative_map_context_region_render;
  PERFORM overview.assert_watertight_administrative_render();
  PERFORM overview.assert_clean_real_named_boundary_render();
END;
$$;

-- V54 publishes the compatible, named-edge-aware surface rule.  Keep this
-- migration limited to helper definitions so an existing named enclave is not
-- rejected before that rule is installed.
