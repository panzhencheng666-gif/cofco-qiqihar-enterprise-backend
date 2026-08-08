ALTER TABLE platform.region
    DROP CONSTRAINT region_administrative_level_check,
    DROP CONSTRAINT region_hierarchy_shape;

DROP TRIGGER region_parent_level_invariant ON platform.region;
DROP FUNCTION platform.enforce_region_parent_level();

ALTER TABLE platform.region
    ADD CONSTRAINT region_administrative_level_check
        CHECK (administrative_level IN ('PREFECTURE', 'COUNTY', 'TOWNSHIP', 'VILLAGE')),
    ADD CONSTRAINT region_hierarchy_shape
        CHECK (
            (administrative_level = 'PREFECTURE' AND parent_code IS NULL)
            OR (
                administrative_level IN ('COUNTY', 'TOWNSHIP', 'VILLAGE')
                AND parent_code IS NOT NULL
                AND parent_code <> code
            )
        );

CREATE FUNCTION platform.enforce_region_parent_level()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    expected_parent_level varchar(20);
    expected_child_level varchar(20);
BEGIN
    expected_parent_level := CASE NEW.administrative_level
        WHEN 'COUNTY' THEN 'PREFECTURE'
        WHEN 'TOWNSHIP' THEN 'COUNTY'
        WHEN 'VILLAGE' THEN 'TOWNSHIP'
        ELSE NULL
    END;
    expected_child_level := CASE NEW.administrative_level
        WHEN 'PREFECTURE' THEN 'COUNTY'
        WHEN 'COUNTY' THEN 'TOWNSHIP'
        WHEN 'TOWNSHIP' THEN 'VILLAGE'
        ELSE NULL
    END;

    IF expected_parent_level IS NOT NULL
       AND NOT EXISTS (
           SELECT 1 FROM platform.region parent
           WHERE parent.code = NEW.parent_code
             AND parent.administrative_level = expected_parent_level
       ) THEN
        RAISE EXCEPTION '% parent must be a %', NEW.administrative_level, expected_parent_level
            USING ERRCODE = '23514';
    END IF;

    IF expected_child_level IS NULL
       AND EXISTS (SELECT 1 FROM platform.region child WHERE child.parent_code = NEW.code) THEN
        RAISE EXCEPTION 'VILLAGE regions cannot have children'
            USING ERRCODE = '23514';
    END IF;

    IF expected_child_level IS NOT NULL
       AND EXISTS (
           SELECT 1 FROM platform.region child
           WHERE child.parent_code = NEW.code
             AND child.administrative_level <> expected_child_level
       ) THEN
        RAISE EXCEPTION '% children must be % regions', NEW.administrative_level, expected_child_level
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;

CREATE CONSTRAINT TRIGGER region_parent_level_invariant
AFTER INSERT OR UPDATE ON platform.region
DEFERRABLE INITIALLY IMMEDIATE
FOR EACH ROW
EXECUTE FUNCTION platform.enforce_region_parent_level();

CREATE TABLE platform.geography_import_batch (
    dataset_sha256 char(64) PRIMARY KEY,
    source_workbook_sha256 char(64) NOT NULL,
    source_revision varchar(80) NOT NULL,
    township_count integer NOT NULL CHECK (township_count > 0),
    village_count integer NOT NULL CHECK (village_count > 0),
    coordinate_count integer NOT NULL CHECK (coordinate_count = village_count),
    imported_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE platform.region_location (
    region_code varchar(12) PRIMARY KEY REFERENCES platform.region(code) ON DELETE CASCADE,
    original_coordinate geometry(Point, 4490) NOT NULL,
    wgs84_coordinate geometry(Point, 4326) NOT NULL,
    original_crs varchar(20) NOT NULL CHECK (original_crs = 'EPSG:4490'),
    target_crs varchar(20) NOT NULL CHECK (target_crs = 'EPSG:4326'),
    conversion_method varchar(240) NOT NULL CHECK (btrim(conversion_method) <> ''),
    source_name varchar(160) NOT NULL CHECK (btrim(source_name) <> ''),
    source_url varchar(500) NOT NULL CHECK (btrim(source_url) <> ''),
    source_revision varchar(80) NOT NULL CHECK (btrim(source_revision) <> ''),
    official_place_id varchar(80),
    official_place_code varchar(80),
    official_standard_name varchar(180),
    official_area_code varchar(40),
    place_type varchar(80) NOT NULL CHECK (btrim(place_type) <> ''),
    matched_by varchar(500) NOT NULL CHECK (btrim(matched_by) <> ''),
    match_confidence varchar(20) NOT NULL CHECK (match_confidence IN ('HIGH', 'MEDIUM', 'LOW')),
    review_status varchar(100) NOT NULL CHECK (btrim(review_status) <> ''),
    review_note varchar(1000),
    dataset_sha256 char(64) NOT NULL REFERENCES platform.geography_import_batch(dataset_sha256),
    loaded_at timestamptz NOT NULL DEFAULT now(),
    CHECK (ST_SRID(original_coordinate) = 4490),
    CHECK (ST_SRID(wgs84_coordinate) = 4326)
);

CREATE INDEX region_location_original_gix
    ON platform.region_location USING GIST (original_coordinate);
CREATE INDEX region_location_wgs84_gix
    ON platform.region_location USING GIST (wgs84_coordinate);
CREATE INDEX region_location_review_status_idx
    ON platform.region_location (review_status, match_confidence);

COMMENT ON COLUMN platform.region.code IS
    'Platform region identifier. Village identifiers are internal 12-character codes and are never represented as national statistical village codes.';
COMMENT ON TABLE platform.region_location IS
    'Source-attributed village points. Original CGCS2000 coordinates and transformed WGS84 presentation points are both retained.';
COMMENT ON COLUMN platform.region_location.review_status IS
    'Geography release gate status; coordinate presence alone does not imply final governance approval.';
