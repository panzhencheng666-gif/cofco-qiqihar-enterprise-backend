-- Source geometry remains the authority for counties and townships. Some
-- upstream source features contain small detached rings, enclaves, or holes;
-- they cannot be drawn as anonymous/un-clickable map surfaces. Reconcile only
-- those residuals through the existing real-core adjacency algorithm before
-- producing the generated village layer.

CREATE OR REPLACE FUNCTION overview.refresh_administrative_boundary_render()
RETURNS void
LANGUAGE plpgsql
AS $$
BEGIN
  PERFORM overview.refresh_administrative_boundary_render_source();

  -- Keeps every county and township's source-derived main boundary as its
  -- immutable core. Only source residuals are assigned through a shared-edge
  -- graph to a named neighbour, so no synthetic regular-grid replacement or
  -- anonymous boundary can be published.
  PERFORM overview.clean_real_township_boundary_render();
  PERFORM overview.close_root_prefecture_gaps('FORMAL_BUSINESS');

  CREATE TEMP TABLE village_parent_source_geometry ON COMMIT DROP AS
    SELECT render.region_code,render.geometry,render.geo_json
      FROM overview.administrative_boundary_render render
      JOIN platform.region region ON region.code=render.region_code
     WHERE region.administrative_level='TOWNSHIP';

  UPDATE overview.administrative_boundary_render render
     SET geometry=overview.village_display_parent_surface(render.geometry),
         geo_json=ST_AsGeoJSON(overview.village_display_parent_surface(render.geometry))
    FROM platform.region region
   WHERE region.code=render.region_code
     AND region.administrative_level='TOWNSHIP';

  PERFORM overview.repartition_display_children_watertight('VILLAGE');

  UPDATE overview.administrative_boundary_render render
     SET geometry=source.geometry,
         geo_json=source.geo_json
    FROM village_parent_source_geometry source
   WHERE render.region_code=source.region_code;

  PERFORM overview.restore_administrative_boundary_render_provenance();
  TRUNCATE overview.administrative_map_context_region_render;
  PERFORM overview.assert_watertight_administrative_render();
END;
$$;

SELECT overview.refresh_administrative_boundary_render();
SELECT overview.refresh_monitoring_scope_boundary_render('FORMAL_BUSINESS');
