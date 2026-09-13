-- Read-only gate for an entry advertised as the populated local business runtime.
-- Run against the exact configured database before exposing the HTTPS proxy.
DO $$
DECLARE missing_count integer;
BEGIN
  IF NOT EXISTS(SELECT 1 FROM registry.sample_point WHERE deletion_state='ACTIVE' AND approval_state='APPROVED') THEN
    RAISE EXCEPTION 'Business data baseline is empty; refusing to expose a fixture-only database as the business entry';
  END IF;
  SELECT count(*) INTO missing_count
    FROM platform.monitoring_scope_region scope
    JOIN platform.region region ON region.code=scope.region_code
    LEFT JOIN overview.administrative_boundary_render boundary ON boundary.region_code=region.code
    WHERE scope.scope_code='FORMAL_BUSINESS' AND scope.included
      AND (boundary.region_code IS NULL OR boundary.geo_json IS NULL);
  IF missing_count > 0 THEN
    RAISE EXCEPTION 'Business map baseline has % missing render boundaries; refusing an incomplete map',missing_count;
  END IF;
  IF NOT EXISTS(SELECT 1 FROM overview.monitoring_scope_boundary_render WHERE scope_code='FORMAL_BUSINESS') THEN
    RAISE EXCEPTION 'Business map overall boundary is missing';
  END IF;
END $$;
