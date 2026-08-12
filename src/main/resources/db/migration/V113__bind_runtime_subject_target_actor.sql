-- V112 was already applied by the managed local runtime before the final
-- target actor was tightened. Preserve that immutable migration checksum and
-- carry the forward-only actor binding in this migration.
INSERT INTO platform.work_unit(code,name,sort_order)
SELECT 'DATABASE_AUTOMATION','数据库受控自动化',COALESCE(max(sort_order),0)+1
FROM platform.work_unit
ON CONFLICT(code) DO NOTHING;

INSERT INTO platform.security_user(subject_id,display_name,work_unit_code)
VALUES('database-master-data-automation','主数据受控自动化','DATABASE_AUTOMATION')
ON CONFLICT(subject_id) DO NOTHING;

CREATE OR REPLACE FUNCTION platform.register_approved_sample_subject(
    source_domain varchar,
    source_record_id varchar,
    target_sample_point_id uuid)
RETURNS bigint
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog,platform,registry,production,market
AS $$
DECLARE
    source_status varchar(30);
    current_sample_point_id uuid;
    stable_subject_id varchar(500);
    approved_at timestamptz;
    target_snapshot jsonb;
BEGIN
    IF source_domain NOT IN ('PRODUCTION','MARKET') THEN
        RAISE EXCEPTION 'unsupported subject source domain';
    END IF;
    IF source_record_id IS NULL OR btrim(source_record_id)='' THEN
        RAISE EXCEPTION 'subject source record is required';
    END IF;

    IF source_domain='PRODUCTION' THEN
        SELECT record.status_code,record.sample_point_id,metadata.value
          INTO source_status,current_sample_point_id,stable_subject_id
        FROM production.production_record record
        JOIN production.production_record_submission_metadata metadata
          ON metadata.record_id=record.record_id
         AND metadata.field_code='PROD_SAMPLE_SUBJECT_CODE'
        WHERE record.record_id=source_record_id;
    ELSE
        SELECT record.status_code,record.sample_point_id,core.value
          INTO source_status,current_sample_point_id,stable_subject_id
        FROM market.market_record record
        JOIN market.market_record_core_value core
          ON core.record_id=record.record_id
         AND core.field_code='MKT_SAMPLE_SUBJECT_CODE'
        WHERE record.record_id=source_record_id;
    END IF;

    IF source_status IS NULL THEN
        RAISE EXCEPTION 'subject source record or stable subject is missing';
    END IF;
    IF source_status<>'APPROVED' OR current_sample_point_id IS NOT NULL THEN
        RAISE EXCEPTION 'subject source must be approved and unlinked';
    END IF;
    SELECT point.updated_at INTO approved_at
    FROM registry.sample_point point
    WHERE point.sample_point_id=target_sample_point_id
      AND point.approval_state='APPROVED';
    IF approved_at IS NULL THEN
        RAISE EXCEPTION 'approved sample point is missing';
    END IF;
    IF session_user=current_user THEN
        RAISE EXCEPTION 'runtime applicant must be distinct from the policy reviewer';
    END IF;

    target_snapshot := jsonb_build_object(
      'business_domain',source_domain,
      'subject_id',stable_subject_id,
      'sample_point_id',target_sample_point_id,
      'created_at',approved_at,
      'created_by','database-master-data-automation');
    RETURN platform.govern_master_data_change(
      'SUBJECT',source_domain || ':' || stable_subject_id,'INSERT',target_snapshot,
      approved_at,session_user::varchar,current_user::varchar,
      source_domain || ' approved record accepted by the database policy owner');
END;
$$;

ALTER FUNCTION platform.register_approved_sample_subject(varchar,varchar,uuid)
OWNER TO qiqihar_migration_owner;
REVOKE ALL ON FUNCTION platform.register_approved_sample_subject(varchar,varchar,uuid)
FROM PUBLIC, qiqihar_master_data_applicant, qiqihar_master_data_reviewer,
    qiqihar_master_data_applier;
GRANT EXECUTE ON FUNCTION platform.register_approved_sample_subject(varchar,varchar,uuid)
TO qiqihar_enterprise_runtime;

COMMENT ON FUNCTION platform.register_approved_sample_subject(varchar,varchar,uuid) IS
    'Runtime-only stable-subject entry; actors are bound to session_user and the fixed policy owner.';
