-- Compare governed SUBJECT snapshots by their typed database value, not by the
-- session-specific text rendering of timestamptz. Equivalent instants remain
-- equivalent when the database runs in UTC or Asia/Shanghai; every other field
-- and the approved target row must still match exactly.
CREATE OR REPLACE FUNCTION platform.guard_controlled_master_data_apply()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog,platform,registry
AS $$
DECLARE
    request_id_value bigint;
    request_row platform.master_data_change_request%ROWTYPE;
    relation_name varchar(160) := TG_TABLE_SCHEMA || '.' || TG_TABLE_NAME;
    source_snapshot jsonb := CASE WHEN TG_OP='DELETE' THEN to_jsonb(OLD) ELSE to_jsonb(NEW) END;
    reviewed_snapshot jsonb;
    stable_key varchar(240);
    authorized_owner name;
BEGIN
    SELECT pg_get_userbyid(proowner) INTO authorized_owner
    FROM pg_proc WHERE oid='platform.apply_master_data_change(bigint)'::regprocedure;
    IF current_user<>authorized_owner THEN
        RAISE EXCEPTION 'canonical master data writes require controlled apply';
    END IF;
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
    IF NOT FOUND OR request_row.target_relation<>relation_name OR request_row.operation_code<>TG_OP THEN
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

    reviewed_snapshot := request_row.target_snapshot;
    IF relation_name='registry.sample_point_subject_identity' THEN
        SELECT to_jsonb(canonical_subject)
        INTO reviewed_snapshot
        FROM jsonb_populate_record(
            NULL::registry.sample_point_subject_identity,
            request_row.target_snapshot) canonical_subject;
    END IF;
    IF source_snapshot<>reviewed_snapshot THEN
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

ALTER FUNCTION platform.guard_controlled_master_data_apply()
OWNER TO qiqihar_migration_owner;
REVOKE ALL ON FUNCTION platform.guard_controlled_master_data_apply()
FROM PUBLIC, qiqihar_enterprise_runtime, qiqihar_master_data_applicant,
    qiqihar_master_data_reviewer, qiqihar_master_data_applier;

COMMENT ON FUNCTION platform.guard_controlled_master_data_apply() IS
    'Rejects every unreviewed canonical write and compares governed subject timestamps as typed instants across database time zones.';
