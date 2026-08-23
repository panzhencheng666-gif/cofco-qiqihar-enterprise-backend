-- A design reference point is the governed WGS84 point for one village. It is
-- yearless reference data, never a survey record and never a registry sample point.
CREATE VIEW registry.village_design_sample_point AS
SELECT village.code AS village_region_code,
       village.name AS village_name,
       township.code AS township_region_code,
       township.name AS township_name,
       county.code AS county_region_code,
       county.name AS county_name,
       prefecture.code AS prefecture_region_code,
       prefecture.name AS prefecture_name,
       ST_X(location.wgs84_coordinate)::numeric(10,7) AS longitude,
       ST_Y(location.wgs84_coordinate)::numeric(10,7) AS latitude,
       location.source_name AS coordinate_source_name,
       location.source_url AS coordinate_source_url,
       location.source_revision AS coordinate_source_revision,
       location.match_confidence AS coordinate_match_confidence,
       location.review_status AS coordinate_review_status,
       location.dataset_sha256 AS coordinate_dataset_sha256
FROM platform.region village
JOIN platform.region township
  ON township.code=village.parent_code
 AND township.administrative_level='TOWNSHIP'
JOIN platform.region county
  ON county.code=township.parent_code
 AND county.administrative_level='COUNTY'
JOIN platform.region prefecture
  ON prefecture.code=county.parent_code
 AND prefecture.administrative_level='PREFECTURE'
JOIN platform.region_location location ON location.region_code=village.code
WHERE village.administrative_level='VILLAGE';

CREATE TABLE registry.sample_network_year (
    network_year smallint PRIMARY KEY,
    status_code varchar(20) NOT NULL DEFAULT 'DRAFT',
    carried_from_year smallint NULL
        REFERENCES registry.sample_network_year(network_year) ON DELETE RESTRICT,
    version bigint NOT NULL DEFAULT 0,
    created_by varchar(120) NOT NULL REFERENCES platform.security_user(subject_id),
    created_at timestamptz NOT NULL DEFAULT now(),
    submitted_by varchar(120) NULL REFERENCES platform.security_user(subject_id),
    submitted_at timestamptz NULL,
    reviewed_by varchar(120) NULL REFERENCES platform.security_user(subject_id),
    reviewed_at timestamptz NULL,
    review_reason varchar(500) NULL,
    published_by varchar(120) NULL REFERENCES platform.security_user(subject_id),
    published_at timestamptz NULL,
    retired_by varchar(120) NULL REFERENCES platform.security_user(subject_id),
    retired_at timestamptz NULL,
    CONSTRAINT sample_network_year_range_check
        CHECK (network_year BETWEEN 2000 AND 2200),
    CONSTRAINT sample_network_year_status_check
        CHECK (status_code IN ('DRAFT','IN_REVIEW','PUBLISHED','RETIRED')),
    CONSTRAINT sample_network_year_source_check
        CHECK (carried_from_year IS NULL OR carried_from_year<>network_year),
    CONSTRAINT sample_network_year_version_check CHECK (version>=0),
    CONSTRAINT sample_network_year_lifecycle_shape_check CHECK (
        (status_code='DRAFT'
          AND submitted_by IS NULL AND submitted_at IS NULL
          AND reviewed_by IS NULL AND reviewed_at IS NULL
          AND published_by IS NULL AND published_at IS NULL
          AND retired_by IS NULL AND retired_at IS NULL)
        OR
        (status_code='IN_REVIEW'
          AND submitted_by IS NOT NULL AND submitted_at IS NOT NULL
          AND reviewed_by IS NULL AND reviewed_at IS NULL
          AND published_by IS NULL AND published_at IS NULL
          AND retired_by IS NULL AND retired_at IS NULL)
        OR
        (status_code='PUBLISHED'
          AND submitted_by IS NOT NULL AND submitted_at IS NOT NULL
          AND reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL
          AND published_by IS NOT NULL AND published_at IS NOT NULL
          AND submitted_by<>reviewed_by
          AND retired_by IS NULL AND retired_at IS NULL)
        OR
        (status_code='RETIRED'
          AND submitted_by IS NOT NULL AND submitted_at IS NOT NULL
          AND reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL
          AND published_by IS NOT NULL AND published_at IS NOT NULL
          AND submitted_by<>reviewed_by
          AND retired_by IS NOT NULL AND retired_at IS NOT NULL)
    )
);

CREATE TABLE registry.sample_network_membership (
    network_year smallint NOT NULL
        REFERENCES registry.sample_network_year(network_year) ON DELETE RESTRICT,
    sample_point_id uuid NOT NULL
        REFERENCES registry.sample_point(sample_point_id) ON DELETE RESTRICT,
    village_region_code varchar(12) NOT NULL
        REFERENCES platform.region(code) ON DELETE RESTRICT,
    status_code varchar(20) NOT NULL DEFAULT 'CANDIDATE',
    source_code varchar(30) NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    decision_reason varchar(500) NULL,
    decided_by varchar(120) NULL REFERENCES platform.security_user(subject_id),
    decided_at timestamptz NULL,
    created_by varchar(120) NOT NULL REFERENCES platform.security_user(subject_id),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (network_year,sample_point_id),
    CONSTRAINT sample_network_membership_status_check
        CHECK (status_code IN ('CANDIDATE','ACTIVE','PAUSED','REMOVED')),
    CONSTRAINT sample_network_membership_source_check
        CHECK (source_code IN ('CARRIED_FORWARD','NEW','MANUAL')),
    CONSTRAINT sample_network_membership_version_check CHECK (version>=0),
    CONSTRAINT sample_network_membership_decision_check CHECK (
        (status_code='CANDIDATE' AND decided_by IS NULL AND decided_at IS NULL)
        OR
        (status_code<>'CANDIDATE' AND decided_by IS NOT NULL AND decided_at IS NOT NULL)
    )
);

CREATE INDEX sample_network_membership_village_lookup
    ON registry.sample_network_membership(network_year,village_region_code,status_code);
CREATE INDEX sample_network_membership_status_lookup
    ON registry.sample_network_membership(network_year,status_code,sample_point_id);

CREATE FUNCTION registry.guard_sample_network_membership_village()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog,platform,registry
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM platform.region
        WHERE code=NEW.village_region_code AND administrative_level='VILLAGE'
    ) THEN
        RAISE EXCEPTION 'sample network membership region must be a village'
            USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER sample_network_membership_village_guard
BEFORE INSERT OR UPDATE OF village_region_code
ON registry.sample_network_membership
FOR EACH ROW EXECUTE FUNCTION registry.guard_sample_network_membership_village();

ALTER VIEW registry.village_design_sample_point OWNER TO qiqihar_migration_owner;
ALTER TABLE registry.sample_network_year OWNER TO qiqihar_migration_owner;
ALTER TABLE registry.sample_network_membership OWNER TO qiqihar_migration_owner;
ALTER FUNCTION registry.guard_sample_network_membership_village()
    OWNER TO qiqihar_migration_owner;

REVOKE ALL ON TABLE
    registry.village_design_sample_point,
    registry.sample_network_year,
    registry.sample_network_membership
FROM PUBLIC;
REVOKE ALL ON FUNCTION registry.guard_sample_network_membership_village()
FROM PUBLIC;

GRANT SELECT ON TABLE registry.village_design_sample_point
TO qiqihar_enterprise_runtime,CURRENT_USER;
GRANT SELECT,INSERT,UPDATE,DELETE ON TABLE
    registry.sample_network_year,
    registry.sample_network_membership
TO qiqihar_enterprise_runtime,CURRENT_USER;

COMMENT ON VIEW registry.village_design_sample_point IS
    'Yearless read-only design references derived one-for-one from governed village locations.';
COMMENT ON TABLE registry.sample_network_year IS
    'Governed annual real-sample network lifecycle; publication requires independent review.';
COMMENT ON TABLE registry.sample_network_membership IS
    'One stable real sample point membership decision per year, explicitly matched to one village design reference.';
