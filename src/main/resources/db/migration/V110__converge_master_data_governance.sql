-- V107 was exercised by the local acceptance runtime while its implementation was
-- still limited to append-only revision snapshots. Complete that local upgrade
-- path without weakening the final V107 contract used by fresh installations.
-- Every statement is idempotent so repaired local databases and fresh databases
-- converge on the same independently reviewed, controlled-apply model.
CREATE TABLE IF NOT EXISTS platform.master_data_change_request (
    request_id bigserial PRIMARY KEY,
    entity_type varchar(30) NOT NULL
        CHECK (entity_type IN ('SUBJECT','REGION','PRODUCT','OBJECT_TYPE')),
    entity_key varchar(240) NOT NULL CHECK (btrim(entity_key)<>''),
    operation_code varchar(20) NOT NULL CHECK (operation_code IN ('INSERT','UPDATE','DELETE')),
    target_relation varchar(160) NOT NULL,
    target_snapshot jsonb NOT NULL CHECK (jsonb_typeof(target_snapshot)='object'),
    requested_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    requested_by varchar(120) NOT NULL CHECK (btrim(requested_by)<>''),
    request_basis text NOT NULL CHECK (btrim(request_basis)<>''),
    effective_at timestamptz NOT NULL
);

CREATE TABLE IF NOT EXISTS platform.master_data_change_event (
    event_id bigserial PRIMARY KEY,
    request_id bigint NOT NULL REFERENCES platform.master_data_change_request(request_id),
    event_type varchar(20) NOT NULL CHECK (event_type IN ('SUBMITTED','APPROVED','REJECTED','APPLIED')),
    occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    actor varchar(120) NOT NULL CHECK (btrim(actor)<>''),
    basis text NOT NULL CHECK (btrim(basis)<>''),
    UNIQUE(request_id,event_type)
);

CREATE INDEX IF NOT EXISTS master_data_change_event_request_order
    ON platform.master_data_change_event(request_id,event_id);

COMMENT ON TABLE platform.master_data_change_request IS
    'Immutable master-data change intent with stable target key, complete target snapshot and explicit effective boundary.';
COMMENT ON TABLE platform.master_data_change_event IS
    'Append-only submit, independent review decision and controlled apply evidence.';

ALTER TABLE platform.master_data_revision
    ADD COLUMN IF NOT EXISTS change_request_id bigint
        REFERENCES platform.master_data_change_request(request_id),
    ADD COLUMN IF NOT EXISTS reviewed_by varchar(120),
    ADD COLUMN IF NOT EXISTS review_basis text;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid='platform.master_data_revision'::regclass
          AND conname='master_data_revision_governance_evidence'
    ) THEN
        ALTER TABLE platform.master_data_revision
            ADD CONSTRAINT master_data_revision_governance_evidence CHECK (
              (operation_code='BASELINE' AND change_request_id IS NULL
               AND reviewed_by IS NULL AND review_basis IS NULL)
              OR
              (operation_code<>'BASELINE' AND change_request_id IS NOT NULL
               AND reviewed_by IS NOT NULL AND btrim(reviewed_by)<>''
               AND review_basis IS NOT NULL AND btrim(review_basis)<>'')
            );
    END IF;
END;
$$;

CREATE OR REPLACE FUNCTION platform.reject_append_only_master_data_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'master data governance relation % is append-only',TG_TABLE_NAME;
END;
$$;

DROP TRIGGER IF EXISTS master_data_revision_immutable ON platform.master_data_revision;
CREATE TRIGGER master_data_revision_immutable
BEFORE UPDATE OR DELETE ON platform.master_data_revision
FOR EACH ROW EXECUTE FUNCTION platform.reject_append_only_master_data_mutation();
DROP TRIGGER IF EXISTS master_data_change_request_immutable ON platform.master_data_change_request;
CREATE TRIGGER master_data_change_request_immutable
BEFORE UPDATE OR DELETE ON platform.master_data_change_request
FOR EACH ROW EXECUTE FUNCTION platform.reject_append_only_master_data_mutation();
DROP TRIGGER IF EXISTS master_data_change_event_immutable ON platform.master_data_change_event;
CREATE TRIGGER master_data_change_event_immutable
BEFORE UPDATE OR DELETE ON platform.master_data_change_event
FOR EACH ROW EXECUTE FUNCTION platform.reject_append_only_master_data_mutation();
DROP FUNCTION IF EXISTS platform.reject_master_data_revision_mutation();

CREATE OR REPLACE FUNCTION platform.submit_master_data_change(
    requested_entity_type varchar,
    requested_entity_key varchar,
    requested_operation varchar,
    requested_snapshot jsonb,
    requested_effective_at timestamptz,
    applicant varchar,
    basis text)
RETURNS bigint
LANGUAGE plpgsql
AS $$
DECLARE
    request_id_value bigint;
    expected_relation varchar(160);
    snapshot_key varchar(240);
BEGIN
    IF requested_entity_type NOT IN ('SUBJECT','REGION','PRODUCT','OBJECT_TYPE') THEN
        RAISE EXCEPTION 'unsupported master data entity type %',requested_entity_type;
    END IF;
    IF requested_operation NOT IN ('INSERT','UPDATE','DELETE') THEN
        RAISE EXCEPTION 'unsupported master data operation %',requested_operation;
    END IF;
    IF requested_snapshot IS NULL OR jsonb_typeof(requested_snapshot)<>'object' THEN
        RAISE EXCEPTION 'master data target snapshot must be a JSON object';
    END IF;
    IF requested_effective_at IS NULL THEN
        RAISE EXCEPTION 'master data effective_at is required';
    END IF;
    IF NULLIF(btrim(applicant),'') IS NULL OR NULLIF(btrim(basis),'') IS NULL THEN
        RAISE EXCEPTION 'master data applicant and request basis are required';
    END IF;

    CASE requested_entity_type
      WHEN 'SUBJECT' THEN
        expected_relation := 'registry.sample_point_subject_identity';
        snapshot_key := (requested_snapshot->>'business_domain') || ':' || (requested_snapshot->>'subject_id');
      WHEN 'REGION' THEN
        expected_relation := 'platform.region';
        snapshot_key := requested_snapshot->>'code';
      WHEN 'PRODUCT' THEN
        expected_relation := 'platform.product';
        snapshot_key := requested_snapshot->>'code';
      WHEN 'OBJECT_TYPE' THEN
        expected_relation := 'platform.object_type';
        snapshot_key := requested_snapshot->>'code';
    END CASE;
    IF snapshot_key IS NULL OR snapshot_key<>requested_entity_key THEN
        RAISE EXCEPTION 'master data target snapshot key % does not match stable key %',snapshot_key,requested_entity_key;
    END IF;

    INSERT INTO platform.master_data_change_request(
      entity_type,entity_key,operation_code,target_relation,target_snapshot,
      requested_by,request_basis,effective_at)
    VALUES(requested_entity_type,requested_entity_key,requested_operation,expected_relation,
      requested_snapshot,applicant,basis,requested_effective_at)
    RETURNING request_id INTO request_id_value;
    INSERT INTO platform.master_data_change_event(request_id,event_type,actor,basis)
    VALUES(request_id_value,'SUBMITTED',applicant,basis);
    RETURN request_id_value;
END;
$$;

CREATE OR REPLACE FUNCTION platform.review_master_data_change(
    reviewed_request_id bigint,
    decision varchar,
    reviewer varchar,
    basis text)
RETURNS boolean
LANGUAGE plpgsql
AS $$
DECLARE
    request_row platform.master_data_change_request%ROWTYPE;
BEGIN
    SELECT * INTO request_row
    FROM platform.master_data_change_request
    WHERE request_id=reviewed_request_id
    FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'master data request % does not exist',reviewed_request_id;
    END IF;
    IF decision NOT IN ('APPROVE','REJECT') THEN
        RAISE EXCEPTION 'master data review decision must be APPROVE or REJECT';
    END IF;
    IF NULLIF(btrim(reviewer),'') IS NULL OR NULLIF(btrim(basis),'') IS NULL THEN
        RAISE EXCEPTION 'master data reviewer and review basis are required';
    END IF;
    IF reviewer=request_row.requested_by THEN
        RAISE EXCEPTION 'master data separation of duties forbids applicant self-review';
    END IF;
    IF EXISTS(SELECT 1 FROM platform.master_data_change_event
              WHERE request_id=reviewed_request_id AND event_type IN ('APPROVED','REJECTED')) THEN
        RAISE EXCEPTION 'master data request % already has a review decision',reviewed_request_id;
    END IF;
    INSERT INTO platform.master_data_change_event(request_id,event_type,actor,basis)
    VALUES(reviewed_request_id,CASE decision WHEN 'APPROVE' THEN 'APPROVED' ELSE 'REJECTED' END,
      reviewer,basis);
    RETURN true;
END;
$$;

CREATE OR REPLACE FUNCTION platform.guard_controlled_master_data_apply()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    request_id_value bigint;
    request_row platform.master_data_change_request%ROWTYPE;
    relation_name varchar(160) := TG_TABLE_SCHEMA || '.' || TG_TABLE_NAME;
    source_snapshot jsonb := CASE WHEN TG_OP='DELETE' THEN to_jsonb(OLD) ELSE to_jsonb(NEW) END;
    stable_key varchar(240);
BEGIN
    BEGIN
        request_id_value := NULLIF(current_setting('application.master_data_apply_request_id',true),'')::bigint;
    EXCEPTION WHEN invalid_text_representation THEN
        request_id_value := NULL;
    END;
    IF request_id_value IS NULL THEN
        RAISE EXCEPTION 'canonical master data writes require controlled apply';
    END IF;
    SELECT * INTO request_row FROM platform.master_data_change_request
    WHERE request_id=request_id_value FOR UPDATE;
    IF NOT FOUND
       OR request_row.target_relation<>relation_name
       OR request_row.operation_code<>TG_OP THEN
        RAISE EXCEPTION 'controlled apply request does not match %.% %',TG_TABLE_SCHEMA,TG_TABLE_NAME,TG_OP;
    END IF;
    IF NOT EXISTS(SELECT 1 FROM platform.master_data_change_event
                  WHERE request_id=request_id_value AND event_type='APPROVED')
       OR EXISTS(SELECT 1 FROM platform.master_data_change_event
                 WHERE request_id=request_id_value AND event_type IN ('REJECTED','APPLIED')) THEN
        RAISE EXCEPTION 'controlled apply request lacks an unused approved review';
    END IF;
    IF clock_timestamp()<request_row.effective_at THEN
        RAISE EXCEPTION 'controlled apply request has not reached effective_at';
    END IF;
    IF source_snapshot<>request_row.target_snapshot THEN
        RAISE EXCEPTION 'controlled apply target snapshot changed after review';
    END IF;
    IF relation_name='registry.sample_point_subject_identity' THEN
        stable_key := (source_snapshot->>'business_domain') || ':' || (source_snapshot->>'subject_id');
    ELSE
        stable_key := source_snapshot->>'code';
    END IF;
    IF stable_key<>request_row.entity_key THEN
        RAISE EXCEPTION 'controlled apply stable target key mismatch';
    END IF;
    RETURN CASE WHEN TG_OP='DELETE' THEN OLD ELSE NEW END;
END;
$$;

DROP TRIGGER IF EXISTS region_master_data_controlled_apply ON platform.region;
CREATE TRIGGER region_master_data_controlled_apply
BEFORE INSERT OR UPDATE OR DELETE ON platform.region
FOR EACH ROW EXECUTE FUNCTION platform.guard_controlled_master_data_apply();
DROP TRIGGER IF EXISTS product_master_data_controlled_apply ON platform.product;
CREATE TRIGGER product_master_data_controlled_apply
BEFORE INSERT OR UPDATE OR DELETE ON platform.product
FOR EACH ROW EXECUTE FUNCTION platform.guard_controlled_master_data_apply();
DROP TRIGGER IF EXISTS object_type_master_data_controlled_apply ON platform.object_type;
CREATE TRIGGER object_type_master_data_controlled_apply
BEFORE INSERT OR UPDATE OR DELETE ON platform.object_type
FOR EACH ROW EXECUTE FUNCTION platform.guard_controlled_master_data_apply();
DROP TRIGGER IF EXISTS subject_master_data_controlled_apply ON registry.sample_point_subject_identity;
CREATE TRIGGER subject_master_data_controlled_apply
BEFORE INSERT OR UPDATE OR DELETE ON registry.sample_point_subject_identity
FOR EACH ROW EXECUTE FUNCTION platform.guard_controlled_master_data_apply();

CREATE OR REPLACE FUNCTION platform.capture_master_data_revision()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    source_snapshot jsonb;
    captured_type varchar(30);
    captured_key varchar(240);
    captured_source varchar(160);
    captured_source_key varchar(240);
    captured_revision integer;
    captured_actor varchar(120);
    captured_request_id bigint;
    captured_reviewer varchar(120);
    captured_review_basis text;
BEGIN
    source_snapshot := CASE WHEN TG_OP='DELETE' THEN to_jsonb(OLD) ELSE to_jsonb(NEW) END;
    IF TG_TABLE_SCHEMA='registry' AND TG_TABLE_NAME='sample_point_subject_identity' THEN
        captured_type := 'SUBJECT';
        captured_key := (source_snapshot->>'business_domain') || ':' || (source_snapshot->>'subject_id');
        captured_source := 'registry.sample_point_subject_identity';
        captured_source_key := source_snapshot->>'sample_point_id';
    ELSIF TG_TABLE_SCHEMA='platform' AND TG_TABLE_NAME='region' THEN
        captured_type := 'REGION';
        captured_key := source_snapshot->>'code';
        captured_source := 'platform.region';
        captured_source_key := captured_key;
    ELSIF TG_TABLE_SCHEMA='platform' AND TG_TABLE_NAME='product' THEN
        captured_type := 'PRODUCT';
        captured_key := source_snapshot->>'code';
        captured_source := 'platform.product';
        captured_source_key := captured_key;
    ELSIF TG_TABLE_SCHEMA='platform' AND TG_TABLE_NAME='object_type' THEN
        captured_type := 'OBJECT_TYPE';
        captured_key := source_snapshot->>'code';
        captured_source := 'platform.object_type';
        captured_source_key := captured_key;
    ELSE
        RAISE EXCEPTION 'unsupported canonical master relation %.%',TG_TABLE_SCHEMA,TG_TABLE_NAME;
    END IF;

    captured_request_id := NULLIF(current_setting('application.master_data_apply_request_id',true),'')::bigint;
    captured_actor := NULLIF(current_setting('application.actor',true),'');
    SELECT actor,basis INTO captured_reviewer,captured_review_basis
    FROM platform.master_data_change_event
    WHERE request_id=captured_request_id AND event_type='APPROVED';
    IF captured_request_id IS NULL OR captured_actor IS NULL OR captured_reviewer IS NULL THEN
        RAISE EXCEPTION 'controlled master data apply evidence is incomplete';
    END IF;

    PERFORM pg_advisory_xact_lock(hashtextextended(captured_type || ':' || captured_key,0));
    SELECT COALESCE(max(revision_no),0)+1 INTO captured_revision
    FROM platform.master_data_revision
    WHERE entity_type=captured_type AND entity_key=captured_key;

    INSERT INTO platform.master_data_revision(
      entity_type,entity_key,revision_no,operation_code,source_relation,source_key,
      governance_state,snapshot,changed_by,change_request_id,reviewed_by,review_basis)
    VALUES(captured_type,captured_key,captured_revision,TG_OP,captured_source,captured_source_key,
      CASE WHEN TG_OP='DELETE' THEN 'RETIRED' ELSE 'ACTIVE' END,source_snapshot,captured_actor,
      captured_request_id,captured_reviewer,captured_review_basis);
    RETURN CASE WHEN TG_OP='DELETE' THEN OLD ELSE NEW END;
END;
$$;

DROP TRIGGER IF EXISTS region_master_data_revision ON platform.region;
CREATE TRIGGER region_master_data_revision
AFTER INSERT OR UPDATE OR DELETE ON platform.region
FOR EACH ROW EXECUTE FUNCTION platform.capture_master_data_revision();
DROP TRIGGER IF EXISTS product_master_data_revision ON platform.product;
CREATE TRIGGER product_master_data_revision
AFTER INSERT OR UPDATE OR DELETE ON platform.product
FOR EACH ROW EXECUTE FUNCTION platform.capture_master_data_revision();
DROP TRIGGER IF EXISTS object_type_master_data_revision ON platform.object_type;
CREATE TRIGGER object_type_master_data_revision
AFTER INSERT OR UPDATE OR DELETE ON platform.object_type
FOR EACH ROW EXECUTE FUNCTION platform.capture_master_data_revision();
DROP TRIGGER IF EXISTS subject_master_data_revision ON registry.sample_point_subject_identity;
CREATE TRIGGER subject_master_data_revision
AFTER INSERT OR UPDATE OR DELETE ON registry.sample_point_subject_identity
FOR EACH ROW EXECUTE FUNCTION platform.capture_master_data_revision();

CREATE OR REPLACE FUNCTION platform.apply_master_data_change(applied_request_id bigint,apply_actor varchar)
RETURNS boolean
LANGUAGE plpgsql
AS $$
DECLARE
    request_row platform.master_data_change_request%ROWTYPE;
    approval_row platform.master_data_change_event%ROWTYPE;
    region_row platform.region%ROWTYPE;
    product_row platform.product%ROWTYPE;
    object_type_row platform.object_type%ROWTYPE;
    subject_row registry.sample_point_subject_identity%ROWTYPE;
    affected integer;
BEGIN
    IF NULLIF(btrim(apply_actor),'') IS NULL THEN
        RAISE EXCEPTION 'master data apply actor is required';
    END IF;
    SELECT * INTO request_row FROM platform.master_data_change_request
    WHERE request_id=applied_request_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'master data request % does not exist',applied_request_id;
    END IF;
    IF EXISTS(SELECT 1 FROM platform.master_data_change_event
              WHERE request_id=applied_request_id AND event_type='APPLIED') THEN
        RETURN false;
    END IF;
    SELECT * INTO approval_row FROM platform.master_data_change_event
    WHERE request_id=applied_request_id AND event_type='APPROVED';
    IF NOT FOUND OR EXISTS(SELECT 1 FROM platform.master_data_change_event
                           WHERE request_id=applied_request_id AND event_type='REJECTED') THEN
        RAISE EXCEPTION 'master data request % requires an approved review before apply',applied_request_id;
    END IF;
    IF clock_timestamp()<request_row.effective_at THEN
        RAISE EXCEPTION 'master data request % has not reached effective_at %',
          applied_request_id,request_row.effective_at;
    END IF;

    PERFORM set_config('application.master_data_apply_request_id',applied_request_id::text,true);
    PERFORM set_config('application.actor',apply_actor,true);

    CASE request_row.entity_type
      WHEN 'REGION' THEN
        SELECT * INTO region_row FROM jsonb_populate_record(NULL::platform.region,request_row.target_snapshot);
        IF request_row.operation_code='INSERT' THEN
          INSERT INTO platform.region SELECT region_row.*;
        ELSIF request_row.operation_code='UPDATE' THEN
          UPDATE platform.region SET name=region_row.name,parent_code=region_row.parent_code,
            administrative_level=region_row.administrative_level,sort_order=region_row.sort_order
          WHERE code=request_row.entity_key;
        ELSE
          DELETE FROM platform.region WHERE code=request_row.entity_key;
        END IF;
      WHEN 'PRODUCT' THEN
        SELECT * INTO product_row FROM jsonb_populate_record(NULL::platform.product,request_row.target_snapshot);
        IF request_row.operation_code='INSERT' THEN
          INSERT INTO platform.product SELECT product_row.*;
        ELSIF request_row.operation_code='UPDATE' THEN
          UPDATE platform.product SET name=product_row.name,sort_order=product_row.sort_order
          WHERE code=request_row.entity_key;
        ELSE
          DELETE FROM platform.product WHERE code=request_row.entity_key;
        END IF;
      WHEN 'OBJECT_TYPE' THEN
        SELECT * INTO object_type_row FROM jsonb_populate_record(NULL::platform.object_type,request_row.target_snapshot);
        IF request_row.operation_code='INSERT' THEN
          INSERT INTO platform.object_type SELECT object_type_row.*;
        ELSIF request_row.operation_code='UPDATE' THEN
          UPDATE platform.object_type SET name=object_type_row.name,
            business_domain=object_type_row.business_domain,sort_order=object_type_row.sort_order,
            overview_enabled=object_type_row.overview_enabled,
            overview_icon_key=object_type_row.overview_icon_key
          WHERE code=request_row.entity_key;
        ELSE
          DELETE FROM platform.object_type WHERE code=request_row.entity_key;
        END IF;
      WHEN 'SUBJECT' THEN
        SELECT * INTO subject_row FROM jsonb_populate_record(
          NULL::registry.sample_point_subject_identity,request_row.target_snapshot);
        IF request_row.operation_code='INSERT' THEN
          INSERT INTO registry.sample_point_subject_identity SELECT subject_row.*;
        ELSIF request_row.operation_code='UPDATE' THEN
          UPDATE registry.sample_point_subject_identity SET sample_point_id=subject_row.sample_point_id,
            created_at=subject_row.created_at,created_by=subject_row.created_by
          WHERE business_domain=subject_row.business_domain AND subject_id=subject_row.subject_id;
        ELSE
          DELETE FROM registry.sample_point_subject_identity
          WHERE business_domain=subject_row.business_domain AND subject_id=subject_row.subject_id;
        END IF;
    END CASE;
    GET DIAGNOSTICS affected=ROW_COUNT;
    IF affected<>1 THEN
        RAISE EXCEPTION 'controlled master data apply affected % rows instead of one',affected;
    END IF;

    PERFORM set_config('application.master_data_apply_request_id','',true);
    PERFORM set_config('application.actor','',true);
    INSERT INTO platform.master_data_change_event(request_id,event_type,actor,basis)
    VALUES(applied_request_id,'APPLIED',apply_actor,
      'Applied after review by ' || approval_row.actor || ': ' || approval_row.basis);
    RETURN true;
END;
$$;

CREATE OR REPLACE FUNCTION platform.govern_master_data_change(
    requested_entity_type varchar,
    requested_entity_key varchar,
    requested_operation varchar,
    requested_snapshot jsonb,
    requested_effective_at timestamptz,
    applicant varchar,
    reviewer varchar,
    review_basis text)
RETURNS bigint
LANGUAGE plpgsql
AS $$
DECLARE
    request_id_value bigint;
BEGIN
    request_id_value := platform.submit_master_data_change(
      requested_entity_type,requested_entity_key,requested_operation,requested_snapshot,
      requested_effective_at,applicant,'Governed canonical master data change');
    PERFORM platform.review_master_data_change(request_id_value,'APPROVE',reviewer,review_basis);
    PERFORM platform.apply_master_data_change(request_id_value,reviewer);
    RETURN request_id_value;
END;
$$;

DROP VIEW IF EXISTS platform.canonical_master_data;
CREATE VIEW platform.canonical_master_data AS
SELECT entity_type,entity_key,
       CASE WHEN entity_type='SUBJECT' THEN snapshot->>'subject_id' ELSE snapshot->>'name' END AS display_name,
       source_relation,source_key,governance_state,revision_no,operation_code,
       changed_at,changed_by,change_request_id,reviewed_by,review_basis,snapshot
FROM (
    SELECT DISTINCT ON (entity_type,entity_key) *
    FROM platform.master_data_revision
    ORDER BY entity_type,entity_key,revision_no DESC
) latest;

COMMENT ON VIEW platform.canonical_master_data IS
    'Latest auditable canonical master data. RETIRED rows remain visible and are never silently reused.';
