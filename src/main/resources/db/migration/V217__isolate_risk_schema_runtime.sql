SET lock_timeout = '2s';
SET statement_timeout = '30s';

REVOKE CREATE ON SCHEMA risk FROM PUBLIC;

ALTER TABLE risk.inventory_source
    DROP CONSTRAINT inventory_source_facility_code_fkey;
ALTER TABLE risk.inventory_source_event
    DROP CONSTRAINT inventory_source_event_facility_code_fkey,
    DROP CONSTRAINT inventory_source_event_target_facility_code_fkey,
    DROP CONSTRAINT inventory_source_event_product_code_fkey;
ALTER TABLE risk.inventory_movement
    DROP CONSTRAINT inventory_movement_facility_code_fkey,
    DROP CONSTRAINT inventory_movement_product_code_fkey;
ALTER TABLE risk.inventory_balance
    DROP CONSTRAINT inventory_balance_facility_code_fkey,
    DROP CONSTRAINT inventory_balance_product_code_fkey;

CREATE INDEX inventory_source_facility_reference_idx
    ON risk.inventory_source(facility_code);
CREATE INDEX inventory_source_event_facility_reference_idx
    ON risk.inventory_source_event(facility_code);
CREATE INDEX inventory_source_event_target_facility_reference_idx
    ON risk.inventory_source_event(target_facility_code)
    WHERE target_facility_code IS NOT NULL;
CREATE INDEX inventory_source_event_product_reference_idx
    ON risk.inventory_source_event(product_code);
CREATE INDEX inventory_movement_facility_reference_idx
    ON risk.inventory_movement(facility_code);
CREATE INDEX inventory_movement_product_reference_idx
    ON risk.inventory_movement(product_code);
CREATE INDEX inventory_balance_facility_reference_idx
    ON risk.inventory_balance(facility_code);
CREATE INDEX inventory_balance_product_reference_idx
    ON risk.inventory_balance(product_code);

CREATE TABLE risk.source_fact_snapshot (
    snapshot_id uuid PRIMARY KEY,
    source_system varchar(80) NOT NULL,
    source_record_type varchar(100) NOT NULL,
    source_record_id varchar(200) NOT NULL,
    source_version varchar(160) NOT NULL,
    business_occurred_at timestamptz NOT NULL,
    ingested_at timestamptz NOT NULL,
    payload_sha256 char(64) NOT NULL CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),
    payload jsonb NOT NULL CHECK (jsonb_typeof(payload)='object'),
    source_status varchar(20) NOT NULL DEFAULT 'CURRENT'
        CHECK (source_status IN ('CURRENT','CORRECTED','WITHDRAWN')),
    UNIQUE (source_system,source_record_type,source_record_id,source_version)
);

CREATE INDEX source_fact_snapshot_ingested_idx
    ON risk.source_fact_snapshot(ingested_at,snapshot_id);
CREATE INDEX source_fact_snapshot_record_idx
    ON risk.source_fact_snapshot(
        source_system,source_record_type,source_record_id,business_occurred_at DESC);

CREATE FUNCTION risk.reject_source_fact_snapshot_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Source fact snapshots are immutable';
END
$$;

CREATE TRIGGER source_fact_snapshot_immutable
BEFORE UPDATE OR DELETE ON risk.source_fact_snapshot
FOR EACH ROW EXECUTE FUNCTION risk.reject_source_fact_snapshot_mutation();

GRANT SELECT,INSERT ON TABLE risk.source_fact_snapshot TO qiqihar_enterprise_runtime;

COMMENT ON TABLE risk.source_fact_snapshot IS
    'Immutable, versioned business fact snapshots copied into the isolated risk schema without cross-schema foreign keys.';
