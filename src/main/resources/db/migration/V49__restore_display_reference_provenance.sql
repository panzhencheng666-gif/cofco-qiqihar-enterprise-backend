-- Topology cleaning is allowed to change the derived render geometry, but it
-- must not overwrite the provenance of the display reference that supplied
-- that geometry.  Keep county/township render metadata aligned with the
-- reference table after every refresh so the publication gate can make an
-- honest source decision.

CREATE OR REPLACE FUNCTION overview.restore_administrative_boundary_render_provenance()
RETURNS void
LANGUAGE sql
AS $$
  UPDATE overview.administrative_boundary_render render
     SET source_geometry_sha256=reference.geometry_sha256,
         source_name=reference.source_name,
         source_revision=reference.source_revision,
         source_license=reference.source_license,
         refreshed_at=now()
    FROM platform.region region
    JOIN overview.administrative_boundary_display_reference reference
      ON reference.region_code=region.code
   WHERE render.region_code=region.code
     AND region.administrative_level IN ('COUNTY','TOWNSHIP');
$$;

CREATE OR REPLACE FUNCTION overview.refresh_administrative_boundary_render()
RETURNS void
LANGUAGE plpgsql
AS $$
BEGIN
  PERFORM overview.refresh_administrative_boundary_render_source();
  PERFORM overview.clean_real_township_boundary_render();
  PERFORM overview.restore_administrative_boundary_render_provenance();
  TRUNCATE overview.administrative_map_context_region_render;
  PERFORM overview.repartition_display_children('VILLAGE');
  PERFORM overview.close_village_boundary_gaps();
  PERFORM overview.close_root_prefecture_gaps('FORMAL_BUSINESS');
END;
$$;

SELECT overview.restore_administrative_boundary_render_provenance();
