CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE overview.administrative_boundary (
    region_code varchar(12) PRIMARY KEY REFERENCES platform.region(code),
    geometry geometry(MultiPolygon, 4326) NOT NULL,
    source_name varchar(160) NOT NULL,
    source_url text NOT NULL,
    source_revision varchar(120) NOT NULL,
    source_license varchar(160) NOT NULL,
    source_feature_id varchar(120),
    source_effective_on date,
    geometry_sha256 char(64) NOT NULL,
    loaded_at timestamptz NOT NULL DEFAULT now(),
    CHECK (ST_IsValid(geometry)),
    CHECK (NOT ST_IsEmpty(geometry)),
    CHECK (ST_SRID(geometry) = 4326)
);

CREATE INDEX administrative_boundary_geometry_gix
    ON overview.administrative_boundary USING GIST (geometry);

COMMENT ON TABLE overview.administrative_boundary IS
    'Versioned, source-attributed administrative boundaries. Geometry is imported from an approved GeoJSON source; parent geometries may be derived only by unioning fully covered child boundaries.';
