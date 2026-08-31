CREATE TABLE platform.design_sample_point (
    design_sample_point_id uuid PRIMARY KEY,
    contract_version varchar(80) NOT NULL
        REFERENCES platform.design_sample_contract(contract_version),
    domain_code varchar(40) NOT NULL,
    product_code varchar(40) NOT NULL,
    object_type_code varchar(80) NOT NULL,
    values_json jsonb NOT NULL CHECK (jsonb_typeof(values_json) = 'object'),
    sample_name varchar(200) NOT NULL CHECK (btrim(sample_name) <> ''),
    region_code varchar(12) NOT NULL REFERENCES platform.region(code),
    governed_point geometry(Point,4326) NOT NULL,
    idempotency_key varchar(200) NOT NULL CHECK (btrim(idempotency_key) <> ''),
    request_digest char(64) NOT NULL CHECK (request_digest ~ '^[a-f0-9]{64}$'),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_by varchar(120) NOT NULL REFERENCES platform.security_user(subject_id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_by varchar(120) NOT NULL REFERENCES platform.security_user(subject_id),
    updated_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (contract_version,domain_code,product_code,object_type_code)
        REFERENCES platform.design_sample_context(
            contract_version,domain_code,product_code,object_type_code),
    UNIQUE (created_by,idempotency_key),
    CHECK (ST_SRID(governed_point) = 4326
        AND ST_NDims(governed_point) = 2
        AND NOT ST_IsEmpty(governed_point)
        AND ST_IsValid(governed_point)
        AND ST_X(governed_point) BETWEEN -180 AND 180
        AND ST_Y(governed_point) BETWEEN -90 AND 90),
    CHECK (updated_at >= created_at)
);

CREATE UNIQUE INDEX design_sample_point_business_identity
    ON platform.design_sample_point(
        domain_code,product_code,object_type_code,region_code,lower(btrim(sample_name)));

CREATE INDEX design_sample_point_page_lookup
    ON platform.design_sample_point(
        domain_code,product_code,object_type_code,region_code,
        updated_at DESC,design_sample_point_id);

CREATE INDEX design_sample_point_governed_point_gix
    ON platform.design_sample_point USING gist(governed_point);

CREATE FUNCTION platform.enforce_design_sample_point_containment()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog,platform,overview
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM overview.administrative_boundary boundary
        WHERE boundary.region_code=NEW.region_code
          AND public.ST_Covers(boundary.geometry,NEW.governed_point)
    ) THEN
        RAISE EXCEPTION
            'design sample point coordinate outside selected administrative region %',
            NEW.region_code
            USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END
$$;

REVOKE ALL ON FUNCTION platform.enforce_design_sample_point_containment() FROM PUBLIC;

CREATE TRIGGER design_sample_point_containment_guard
BEFORE INSERT OR UPDATE OF region_code,governed_point
ON platform.design_sample_point
FOR EACH ROW EXECUTE FUNCTION platform.enforce_design_sample_point_containment();

REVOKE ALL ON TABLE platform.design_sample_point FROM PUBLIC;
GRANT SELECT,INSERT,UPDATE,DELETE ON TABLE platform.design_sample_point
TO qiqihar_enterprise_runtime,CURRENT_USER;

COMMENT ON TABLE platform.design_sample_point IS
    'Year-independent design-sample reference master data, separate from formal sample identities, annual sample networks, operational ledgers and legacy region locations.';
COMMENT ON COLUMN platform.design_sample_point.values_json IS
    'Editable values validated against the immutable V157 contract bound by contract_version and context.';
COMMENT ON COLUMN platform.design_sample_point.governed_point IS
    'WGS84 point accepted only after coverage by the selected authoritative administrative boundary is verified.';
