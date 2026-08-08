CREATE TABLE overview.administrative_map_context_region (
  code varchar(64) PRIMARY KEY,
  name varchar(160) NOT NULL,
  parent_code varchar(12) NOT NULL REFERENCES platform.region(code) ON DELETE CASCADE,
  administrative_level varchar(20) NOT NULL CHECK (administrative_level IN ('COUNTY','TOWNSHIP')),
  geometry geometry(MultiPolygon, 4326) NOT NULL,
  boundary_geo_json text NOT NULL,
  source_name varchar(160) NOT NULL,
  source_url text NOT NULL,
  source_revision varchar(120) NOT NULL,
  source_license text NOT NULL,
  source_feature_id varchar(160) NOT NULL,
  geometry_sha256 char(64) NOT NULL,
  sort_order integer NOT NULL DEFAULT 100000,
  loaded_at timestamptz NOT NULL DEFAULT now(),
  CHECK (ST_IsValid(geometry)),
  CHECK (NOT ST_IsEmpty(geometry)),
  CHECK (ST_SRID(geometry)=4326)
);

CREATE INDEX administrative_map_context_region_parent_idx
  ON overview.administrative_map_context_region(parent_code, administrative_level, sort_order, name);

CREATE INDEX administrative_map_context_region_geometry_gix
  ON overview.administrative_map_context_region USING gist(geometry);

COMMENT ON TABLE overview.administrative_map_context_region IS
  'Real source-attributed administrative/operational map areas that fill parent-map gaps but are intentionally excluded from FORMAL_BUSINESS statistics.';
