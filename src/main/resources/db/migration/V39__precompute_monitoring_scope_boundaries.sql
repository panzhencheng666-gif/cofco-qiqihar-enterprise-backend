CREATE TABLE overview.monitoring_scope_boundary (
    scope_code varchar(40) PRIMARY KEY REFERENCES platform.monitoring_scope(code),
    geometry geometry(MultiPolygon, 4326) NOT NULL,
    source_name varchar(160) NOT NULL,
    source_revision varchar(120) NOT NULL,
    source_license text NOT NULL,
    component_geometry_fingerprint text NOT NULL,
    refreshed_at timestamptz NOT NULL DEFAULT now(),
    CHECK (ST_IsValid(geometry)),
    CHECK (NOT ST_IsEmpty(geometry)),
    CHECK (ST_SRID(geometry) = 4326)
);

CREATE INDEX monitoring_scope_boundary_geometry_gix
    ON overview.monitoring_scope_boundary USING GIST (geometry);

CREATE OR REPLACE FUNCTION overview.refresh_monitoring_scope_boundary(requested_scope_code varchar)
RETURNS void
LANGUAGE plpgsql
AS $$
DECLARE
    expected_count integer;
    source_count integer;
    combined_geometry geometry(MultiPolygon, 4326);
    combined_revisions text;
    combined_licenses text;
    combined_fingerprint text;
BEGIN
    SELECT count(*)
      INTO expected_count
      FROM platform.monitoring_scope_region scoped
      JOIN platform.region region ON region.code = scoped.region_code
     WHERE scoped.scope_code = requested_scope_code
       AND scoped.included
       AND region.administrative_level = 'PREFECTURE';

    SELECT count(*),
           ST_Multi(ST_UnaryUnion(ST_Collect(boundary.geometry))),
           string_agg(DISTINCT boundary.source_revision, ', ' ORDER BY boundary.source_revision),
           string_agg(DISTINCT boundary.source_license, '; ' ORDER BY boundary.source_license),
           string_agg(boundary.region_code || ':' || boundary.geometry_sha256, '|' ORDER BY boundary.region_code)
      INTO source_count, combined_geometry, combined_revisions, combined_licenses, combined_fingerprint
      FROM platform.monitoring_scope_region scoped
      JOIN platform.region region ON region.code = scoped.region_code
      JOIN overview.administrative_boundary boundary ON boundary.region_code = region.code
     WHERE scoped.scope_code = requested_scope_code
       AND scoped.included
       AND region.administrative_level = 'PREFECTURE';

    IF expected_count = 0 THEN
        RAISE EXCEPTION 'Cannot refresh unknown or empty monitoring scope %', requested_scope_code;
    END IF;

    -- Flyway must remain runnable before the separately governed boundary
    -- bundle is imported. An incomplete bundle cannot produce a partial scope
    -- shell, so clear any stale derivative and wait for the importer to call
    -- this function again after all prefectures are present.
    IF source_count <> expected_count OR combined_geometry IS NULL THEN
        DELETE FROM overview.monitoring_scope_boundary
         WHERE scope_code = requested_scope_code;
        RETURN;
    END IF;

    INSERT INTO overview.monitoring_scope_boundary(
        scope_code,
        geometry,
        source_name,
        source_revision,
        source_license,
        component_geometry_fingerprint,
        refreshed_at
    )
    VALUES (
        requested_scope_code,
        combined_geometry,
        'Precomputed union of fully covered prefecture boundaries',
        combined_revisions,
        combined_licenses,
        combined_fingerprint,
        now()
    )
    ON CONFLICT(scope_code) DO UPDATE SET
        geometry = EXCLUDED.geometry,
        source_name = EXCLUDED.source_name,
        source_revision = EXCLUDED.source_revision,
        source_license = EXCLUDED.source_license,
        component_geometry_fingerprint = EXCLUDED.component_geometry_fingerprint,
        refreshed_at = EXCLUDED.refreshed_at;
END;
$$;

SELECT overview.refresh_monitoring_scope_boundary('FORMAL_BUSINESS');

COMMENT ON TABLE overview.monitoring_scope_boundary IS
    'Precomputed immutable request-time outline for a governed monitoring scope. It removes browser-side polygon union while retaining the exact source boundary silhouette.';

COMMENT ON FUNCTION overview.refresh_monitoring_scope_boundary(varchar) IS
    'Rebuilds a scope outline only when every included top-level prefecture has a governed real boundary; otherwise removes any stale derivative without inventing a partial shell.';
