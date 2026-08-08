CREATE TABLE overview.administrative_boundary_render (
    region_code varchar(12) PRIMARY KEY REFERENCES overview.administrative_boundary(region_code) ON DELETE CASCADE,
    geometry geometry(MultiPolygon, 4326) NOT NULL,
    geo_json text NOT NULL,
    simplify_tolerance double precision NOT NULL,
    full_point_count integer NOT NULL,
    render_point_count integer NOT NULL,
    source_geometry_sha256 char(64) NOT NULL,
    refreshed_at timestamptz NOT NULL DEFAULT now(),
    CHECK (ST_IsValid(geometry)),
    CHECK (NOT ST_IsEmpty(geometry)),
    CHECK (ST_SRID(geometry) = 4326),
    CHECK (render_point_count > 0),
    CHECK (render_point_count <= full_point_count)
);

CREATE INDEX administrative_boundary_render_geometry_gix
    ON overview.administrative_boundary_render USING GIST (geometry);

CREATE TABLE overview.monitoring_scope_boundary_render (
    scope_code varchar(40) PRIMARY KEY REFERENCES overview.monitoring_scope_boundary(scope_code) ON DELETE CASCADE,
    geometry geometry(MultiPolygon, 4326) NOT NULL,
    geo_json text NOT NULL,
    simplify_tolerance double precision NOT NULL,
    full_point_count integer NOT NULL,
    render_point_count integer NOT NULL,
    component_geometry_fingerprint text NOT NULL,
    refreshed_at timestamptz NOT NULL DEFAULT now(),
    CHECK (ST_IsValid(geometry)),
    CHECK (NOT ST_IsEmpty(geometry)),
    CHECK (ST_SRID(geometry) = 4326),
    CHECK (render_point_count > 0),
    CHECK (render_point_count <= full_point_count)
);

CREATE INDEX monitoring_scope_boundary_render_geometry_gix
    ON overview.monitoring_scope_boundary_render USING GIST (geometry);

CREATE OR REPLACE FUNCTION overview.refresh_administrative_boundary_render()
RETURNS void
LANGUAGE sql
AS $$
    INSERT INTO overview.administrative_boundary_render(
        region_code,
        geometry,
        geo_json,
        simplify_tolerance,
        full_point_count,
        render_point_count,
        source_geometry_sha256,
        refreshed_at
    )
    SELECT source.region_code,
           simplified.geometry,
           ST_AsGeoJSON(simplified.geometry),
           source.tolerance,
           ST_NPoints(source.geometry),
           ST_NPoints(simplified.geometry),
           source.geometry_sha256,
           now()
      FROM (
        SELECT boundary.region_code,
               boundary.geometry,
               boundary.geometry_sha256,
               CASE region.administrative_level
                 WHEN 'PREFECTURE' THEN 0.002
                 WHEN 'COUNTY' THEN 0.0008
                 WHEN 'TOWNSHIP' THEN 0.00035
                 WHEN 'VILLAGE' THEN 0.00012
               END::double precision tolerance
          FROM overview.administrative_boundary boundary
          JOIN platform.region region ON region.code=boundary.region_code
      ) source
      CROSS JOIN LATERAL (
        SELECT ST_Multi(ST_SimplifyPreserveTopology(source.geometry, source.tolerance)) geometry
      ) simplified
    ON CONFLICT(region_code) DO UPDATE SET
        geometry=EXCLUDED.geometry,
        geo_json=EXCLUDED.geo_json,
        simplify_tolerance=EXCLUDED.simplify_tolerance,
        full_point_count=EXCLUDED.full_point_count,
        render_point_count=EXCLUDED.render_point_count,
        source_geometry_sha256=EXCLUDED.source_geometry_sha256,
        refreshed_at=EXCLUDED.refreshed_at;

    DELETE FROM overview.administrative_boundary_render render
     WHERE NOT EXISTS (
       SELECT 1 FROM overview.administrative_boundary boundary
        WHERE boundary.region_code=render.region_code
     );
$$;

CREATE OR REPLACE FUNCTION overview.refresh_monitoring_scope_boundary_render(requested_scope_code varchar)
RETURNS void
LANGUAGE sql
AS $$
    INSERT INTO overview.monitoring_scope_boundary_render(
        scope_code,
        geometry,
        geo_json,
        simplify_tolerance,
        full_point_count,
        render_point_count,
        component_geometry_fingerprint,
        refreshed_at
    )
    SELECT boundary.scope_code,
           simplified.geometry,
           ST_AsGeoJSON(simplified.geometry),
           0.002::double precision,
           ST_NPoints(boundary.geometry),
           ST_NPoints(simplified.geometry),
           boundary.component_geometry_fingerprint,
           now()
      FROM overview.monitoring_scope_boundary boundary
      CROSS JOIN LATERAL (
        SELECT ST_Multi(ST_SimplifyPreserveTopology(boundary.geometry, 0.002)) geometry
      ) simplified
     WHERE boundary.scope_code=requested_scope_code
    ON CONFLICT(scope_code) DO UPDATE SET
        geometry=EXCLUDED.geometry,
        geo_json=EXCLUDED.geo_json,
        simplify_tolerance=EXCLUDED.simplify_tolerance,
        full_point_count=EXCLUDED.full_point_count,
        render_point_count=EXCLUDED.render_point_count,
        component_geometry_fingerprint=EXCLUDED.component_geometry_fingerprint,
        refreshed_at=EXCLUDED.refreshed_at;
$$;

SELECT overview.refresh_administrative_boundary_render();
SELECT overview.refresh_monitoring_scope_boundary_render('FORMAL_BUSINESS');

COMMENT ON TABLE overview.administrative_boundary_render IS
    'Precomputed display-only simplification and serialized GeoJSON. Full governed geometries remain in overview.administrative_boundary for audit and spatial validation.';

COMMENT ON TABLE overview.monitoring_scope_boundary_render IS
    'Precomputed display-only monitoring scope outline. It prevents request-time union, simplification, and GeoJSON serialization.';
