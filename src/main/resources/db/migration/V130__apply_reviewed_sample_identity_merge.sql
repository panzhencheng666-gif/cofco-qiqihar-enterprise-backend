-- Runtime identity merges must cross a review-bound database privilege boundary.
-- The application role cannot insert resolution batches/items or call the
-- actor-bearing implementation directly; this entry derives every mutable
-- value and the human reviewer from immutable business audit events.
-- PostgreSQL row-locking SELECTs require UPDATE privilege even though neither
-- this entry nor the private applier changes the source business rows. The
-- NOLOGIN migration owner receives that capability; the runtime role does not
-- receive any new direct table grant here.
GRANT USAGE ON SCHEMA public TO qiqihar_migration_owner;
GRANT SELECT,UPDATE ON TABLE
  production.production_record,
  market.market_record
TO qiqihar_migration_owner;

CREATE FUNCTION registry.apply_reviewed_sample_identity_merge(
  p_request_id uuid,p_batch_id uuid,p_input_digest char(64))
RETURNS varchar
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog,registry,production,market,platform
AS $$
DECLARE
    submitted_detail jsonb;
    submitted_work_unit varchar(60);
    approval_detail jsonb;
    reviewer_actor varchar(120);
    approval_work_unit varchar(60);
    source_domain varchar(30);
    source_record_id varchar(36);
    expected_source_version bigint;
    current_sample_point_id uuid;
    target_sample_point_id uuid;
    governed_region_code varchar(12);
    submitted_longitude numeric;
    submitted_latitude numeric;
    requested_by varchar(120);
    stable_subject_id varchar(160);
    current_version bigint;
    source_sample_point_id uuid;
    source_status varchar(30);
    source_region_code varchar(12);
    current_point registry.sample_point%ROWTYPE;
    target_point registry.sample_point%ROWTYPE;
    existing_batch registry.sample_subject_resolution_batch%ROWTYPE;
    existing_item registry.sample_subject_resolution_item%ROWTYPE;
    apply_result varchar;
BEGIN
    IF p_request_id IS NULL OR p_batch_id IS NULL THEN
        RAISE EXCEPTION 'identity merge request and batch are required';
    END IF;
    IF p_input_digest IS NULL OR p_input_digest !~ '^[0-9a-f]{64}$' THEN
        RAISE EXCEPTION 'identity merge input digest is invalid';
    END IF;

    SELECT event.detail,event.work_unit_code
      INTO submitted_detail,submitted_work_unit
      FROM platform.business_audit_event event
     WHERE event.aggregate_type='SAMPLE_IDENTITY_MERGE_REQUEST'
       AND event.aggregate_id=p_request_id::text
       AND event.action_code='SAMPLE_IDENTITY_MERGE_SUBMITTED'
     ORDER BY event.occurred_at,event.event_id
     LIMIT 1;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'identity merge submitted request is missing';
    END IF;

    SELECT event.detail,event.actor_subject_id,event.work_unit_code
      INTO approval_detail,reviewer_actor,approval_work_unit
      FROM platform.business_audit_event event
     WHERE event.aggregate_type='SAMPLE_IDENTITY_MERGE_REQUEST'
       AND event.aggregate_id=p_request_id::text
       AND event.action_code='SAMPLE_IDENTITY_MERGE_APPROVAL_AUTHORIZED'
     ORDER BY event.occurred_at DESC,event.event_id DESC
     LIMIT 1;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'identity merge review authorization is missing';
    END IF;
    IF EXISTS(
      SELECT 1 FROM platform.business_audit_event decision
       WHERE decision.aggregate_type='SAMPLE_IDENTITY_MERGE_REQUEST'
         AND decision.aggregate_id=p_request_id::text
         AND decision.action_code IN (
           'SAMPLE_IDENTITY_MERGE_APPLIED','SAMPLE_IDENTITY_MERGE_REJECTED')) THEN
        RAISE EXCEPTION 'identity merge request already has a final decision';
    END IF;

    BEGIN
        source_domain:=submitted_detail->>'sourceDomain';
        source_record_id:=submitted_detail->>'sourceRecordId';
        expected_source_version:=(submitted_detail->>'expectedSourceVersion')::bigint;
        current_sample_point_id:=(submitted_detail->>'currentSamplePointId')::uuid;
        target_sample_point_id:=(submitted_detail->>'targetSamplePointId')::uuid;
        governed_region_code:=submitted_detail->>'regionCode';
        submitted_longitude:=(submitted_detail->>'longitude')::numeric;
        submitted_latitude:=(submitted_detail->>'latitude')::numeric;
        requested_by:=submitted_detail->>'requestedBy';
    EXCEPTION WHEN invalid_text_representation OR numeric_value_out_of_range THEN
        RAISE EXCEPTION 'identity merge submitted request is malformed';
    END;
    IF source_domain IS NULL OR source_domain NOT IN ('PRODUCTION','MARKET')
       OR source_record_id IS NULL OR btrim(source_record_id)=''
       OR expected_source_version IS NULL OR expected_source_version<0
       OR governed_region_code IS NULL OR btrim(governed_region_code)=''
       OR requested_by IS NULL OR btrim(requested_by)=''
       OR current_sample_point_id IS NULL OR target_sample_point_id IS NULL
       OR current_sample_point_id=target_sample_point_id
       OR submitted_longitude IS NULL OR submitted_latitude IS NULL THEN
        RAISE EXCEPTION 'identity merge submitted request is incomplete';
    END IF;
    stable_subject_id:='GOVERNED_SAMPLE:' || source_domain || ':' || target_sample_point_id::text;

    IF submitted_work_unit IS DISTINCT FROM approval_work_unit
       OR submitted_work_unit IS DISTINCT FROM submitted_detail->>'workUnitCode'
       OR approval_detail->>'requestId' IS DISTINCT FROM p_request_id::text
       OR approval_detail->>'sourceDomain' IS DISTINCT FROM source_domain
       OR approval_detail->>'sourceRecordId' IS DISTINCT FROM source_record_id
       OR approval_detail->>'currentSamplePointId' IS DISTINCT FROM current_sample_point_id::text
       OR approval_detail->>'targetSamplePointId' IS DISTINCT FROM target_sample_point_id::text
       OR approval_detail->>'regionCode' IS DISTINCT FROM governed_region_code
       OR approval_detail->>'requestedBy' IS DISTINCT FROM requested_by
       OR nullif(btrim(approval_detail->>'reason'),'') IS NULL THEN
        RAISE EXCEPTION 'identity merge review authorization does not match the submitted request';
    END IF;
    IF NOT EXISTS(
      SELECT 1
        FROM platform.security_user reviewer
        JOIN platform.security_user_role user_role
          ON user_role.subject_id=reviewer.subject_id
        JOIN platform.access_role role
          ON role.code=user_role.role_code AND role.active
        JOIN platform.access_role_permission role_permission
          ON role_permission.role_code=role.code
        JOIN platform.access_permission permission
          ON permission.code=role_permission.permission_code AND permission.active
       WHERE reviewer.subject_id=reviewer_actor
         AND reviewer.enabled
         AND reviewer.work_unit_code=approval_work_unit
         AND permission.code='BUSINESS_APPROVE') THEN
        RAISE EXCEPTION 'identity merge reviewer is not currently authorized';
    END IF;
    IF reviewer_actor=requested_by THEN
        IF coalesce((approval_detail->>'privilegedSelfReview')::boolean,false) IS NOT TRUE
           OR NOT EXISTS(
             SELECT 1 FROM platform.security_user_role owner_role
              WHERE owner_role.subject_id=reviewer_actor
                AND owner_role.role_code='ACCOUNT_OWNER') THEN
            RAISE EXCEPTION 'identity merge self review is not authorized';
        END IF;
    ELSIF coalesce((approval_detail->>'privilegedSelfReview')::boolean,false) THEN
        RAISE EXCEPTION 'identity merge independent review cannot claim owner self review';
    END IF;

    IF source_domain='PRODUCTION' THEN
        SELECT record.version,record.sample_point_id,record.status_code,record.region_code
          INTO current_version,source_sample_point_id,source_status,source_region_code
          FROM production.production_record record
         WHERE record.record_id=source_record_id
         FOR SHARE;
    ELSE
        SELECT record.version,record.sample_point_id,record.status_code,record.region_code
          INTO current_version,source_sample_point_id,source_status,source_region_code
          FROM market.market_record record
         WHERE record.record_id=source_record_id
         FOR SHARE;
    END IF;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'identity merge source record is missing';
    END IF;
    IF current_version<>expected_source_version
       OR source_sample_point_id<>current_sample_point_id
       OR source_status<>'APPROVED'
       OR source_region_code<>governed_region_code THEN
        RAISE EXCEPTION 'identity merge source record is stale';
    END IF;

    SELECT point.* INTO current_point
      FROM registry.sample_point point
     WHERE point.sample_point_id=current_sample_point_id
     FOR SHARE;
    IF NOT FOUND THEN RAISE EXCEPTION 'identity merge current sample point is missing'; END IF;
    SELECT point.* INTO target_point
      FROM registry.sample_point point
     WHERE point.sample_point_id=target_sample_point_id
     FOR SHARE;
    IF NOT FOUND THEN RAISE EXCEPTION 'identity merge target sample point is missing'; END IF;
    IF current_point.kind_code<>'SURVEY_SITE'
       OR target_point.kind_code<>'SURVEY_SITE'
       OR current_point.approval_state<>'APPROVED'
       OR target_point.approval_state<>'APPROVED'
       OR current_point.location_state<>'VALID'
       OR target_point.location_state<>'VALID'
       OR current_point.governed_point IS NULL
       OR target_point.governed_point IS NULL
       OR current_point.region_code<>governed_region_code
       OR target_point.region_code<>governed_region_code
       OR NOT public.ST_Equals(current_point.governed_point,target_point.governed_point)
       OR NOT public.ST_Equals(target_point.governed_point,
            public.ST_SetSRID(public.ST_MakePoint(
              submitted_longitude,submitted_latitude),4326)) THEN
        RAISE EXCEPTION 'identity merge sample points no longer match the reviewed identity evidence';
    END IF;

    SELECT * INTO existing_batch
      FROM registry.sample_subject_resolution_batch batch
     WHERE batch.batch_id=p_batch_id
     FOR UPDATE;
    IF FOUND THEN
        IF existing_batch.idempotency_key<>'sample-identity-merge-' || p_request_id::text
           OR existing_batch.input_digest<>p_input_digest
           OR existing_batch.expected_item_count<>1
           OR existing_batch.created_by<>reviewer_actor THEN
            RAISE EXCEPTION 'identity merge batch idempotency conflict';
        END IF;
    ELSE
        INSERT INTO registry.sample_subject_resolution_batch(
          batch_id,idempotency_key,input_digest,expected_item_count,status_code,created_at,created_by)
        VALUES(p_batch_id,'sample-identity-merge-' || p_request_id::text,
          p_input_digest,1,'STAGED',now(),reviewer_actor);
    END IF;

    SELECT * INTO existing_item
      FROM registry.sample_subject_resolution_item item
     WHERE item.batch_id=p_batch_id AND item.item_sequence=1
     FOR UPDATE;
    IF FOUND THEN
        IF existing_item.source_domain<>source_domain
           OR existing_item.source_record_id<>source_record_id
           OR existing_item.expected_source_version<>expected_source_version
           OR existing_item.resolution_action<>'LINK'
           OR existing_item.stable_subject_id<>stable_subject_id
           OR existing_item.target_sample_point_id<>target_sample_point_id
           OR existing_item.reason_code<>'HISTORICAL_IDENTITY_MERGE' THEN
            RAISE EXCEPTION 'identity merge batch item idempotency conflict';
        END IF;
    ELSE
        INSERT INTO registry.sample_subject_resolution_item(
          batch_id,item_sequence,source_domain,source_record_id,expected_source_version,
          resolution_action,stable_subject_id,target_sample_point_id,reason_code,status_code)
        VALUES(p_batch_id,1,source_domain,source_record_id,expected_source_version,
          'LINK',stable_subject_id,target_sample_point_id,'HISTORICAL_IDENTITY_MERGE','STAGED');
    END IF;

    apply_result:=registry.apply_sample_subject_resolution(p_batch_id,reviewer_actor);
    IF apply_result NOT IN ('APPLIED','ALREADY_APPLIED') THEN
        RAISE EXCEPTION 'identity merge apply returned an invalid result';
    END IF;
    RETURN apply_result;
END;
$$;

ALTER FUNCTION registry.apply_reviewed_sample_identity_merge(uuid,uuid,char)
OWNER TO qiqihar_migration_owner;
REVOKE ALL ON FUNCTION registry.apply_reviewed_sample_identity_merge(uuid,uuid,char)
FROM PUBLIC,qiqihar_enterprise_runtime,qiqihar_master_data_applicant,
  qiqihar_master_data_reviewer,qiqihar_master_data_applier;
GRANT EXECUTE ON FUNCTION registry.apply_reviewed_sample_identity_merge(uuid,uuid,char)
TO qiqihar_enterprise_runtime;

COMMENT ON FUNCTION registry.apply_reviewed_sample_identity_merge(uuid,uuid,char) IS
  'Review-bound runtime entry for append-only historical sample identity merges.';
