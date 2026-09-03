CREATE OR REPLACE FUNCTION platform.guard_controlled_master_data_apply()
RETURNS trigger
LANGUAGE plpgsql
SET search_path=pg_catalog,platform,registry
AS $function$
DECLARE
    request_id_value bigint;
    request_row platform.master_data_change_request%ROWTYPE;
    relation_name varchar(160) := TG_TABLE_SCHEMA || '.' || TG_TABLE_NAME;
    source_snapshot jsonb := CASE WHEN TG_OP='DELETE' THEN to_jsonb(OLD) ELSE to_jsonb(NEW) END;
    reviewed_snapshot jsonb;
    stable_key varchar(240);
    authorized_owner name;
    physical_deletion_id uuid;
BEGIN
    BEGIN
        physical_deletion_id := NULLIF(
            current_setting('application.formal_sample_delete_id',true),'')::uuid;
    EXCEPTION WHEN invalid_text_representation THEN
        physical_deletion_id := NULL;
    END;
    IF relation_name='registry.sample_point_subject_identity'
       AND TG_OP='DELETE'
       AND current_user='qiqihar_migration_owner'
       AND physical_deletion_id IS NOT NULL THEN
        IF OLD.sample_point_id=physical_deletion_id THEN
            RETURN OLD;
        END IF;
    END IF;

    SELECT pg_get_userbyid(proowner) INTO authorized_owner
    FROM pg_proc WHERE oid='platform.apply_master_data_change(bigint)'::regprocedure;
    IF current_user<>authorized_owner THEN
        RAISE EXCEPTION 'canonical master data writes require controlled apply';
    END IF;
    BEGIN
        request_id_value := NULLIF(
            current_setting('application.master_data_apply_request_id',true),'')::bigint;
    EXCEPTION WHEN invalid_text_representation THEN
        request_id_value := NULL;
    END;
    IF request_id_value IS NULL THEN
        RAISE EXCEPTION 'canonical master data writes require controlled apply';
    END IF;
    SELECT * INTO request_row FROM platform.master_data_change_request
    WHERE request_id=request_id_value FOR UPDATE;
    IF NOT FOUND OR request_row.target_relation<>relation_name
       OR request_row.operation_code<>TG_OP THEN
        RAISE EXCEPTION 'controlled apply request does not match %.% %',
            TG_TABLE_SCHEMA,TG_TABLE_NAME,TG_OP;
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
        stable_key := (source_snapshot->>'business_domain') || ':'
            || (source_snapshot->>'subject_id');
    ELSE
        stable_key := source_snapshot->>'code';
    END IF;
    IF stable_key<>request_row.entity_key THEN
        RAISE EXCEPTION 'controlled apply stable target key mismatch';
    END IF;
    RETURN CASE WHEN TG_OP='DELETE' THEN OLD ELSE NEW END;
END;
$function$;

ALTER FUNCTION platform.guard_controlled_master_data_apply()
    OWNER TO qiqihar_migration_owner;
REVOKE ALL ON FUNCTION platform.guard_controlled_master_data_apply()
FROM PUBLIC,qiqihar_enterprise_runtime,qiqihar_master_data_applicant,
    qiqihar_master_data_reviewer,qiqihar_master_data_applier;

CREATE OR REPLACE FUNCTION platform.capture_master_data_revision()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
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
    physical_deletion_id uuid;
BEGIN
    source_snapshot := CASE WHEN TG_OP='DELETE' THEN to_jsonb(OLD) ELSE to_jsonb(NEW) END;
    BEGIN
        physical_deletion_id := NULLIF(
            current_setting('application.formal_sample_delete_id',true),'')::uuid;
    EXCEPTION WHEN invalid_text_representation THEN
        physical_deletion_id := NULL;
    END;
    IF TG_TABLE_SCHEMA='registry'
       AND TG_TABLE_NAME='sample_point_subject_identity'
       AND TG_OP='DELETE'
       AND current_user='qiqihar_migration_owner'
       AND physical_deletion_id IS NOT NULL THEN
        IF OLD.sample_point_id=physical_deletion_id THEN
            RETURN OLD;
        END IF;
    END IF;

    IF TG_TABLE_SCHEMA='registry' AND TG_TABLE_NAME='sample_point_subject_identity' THEN
        captured_type := 'SUBJECT';
        captured_key := (source_snapshot->>'business_domain') || ':'
            || (source_snapshot->>'subject_id');
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
        RAISE EXCEPTION 'unsupported canonical master relation %.%',
            TG_TABLE_SCHEMA,TG_TABLE_NAME;
    END IF;

    captured_request_id := NULLIF(
        current_setting('application.master_data_apply_request_id',true),'')::bigint;
    captured_actor := NULLIF(current_setting('application.actor',true),'');
    SELECT actor,basis INTO captured_reviewer,captured_review_basis
    FROM platform.master_data_change_event
    WHERE request_id=captured_request_id AND event_type='APPROVED';
    IF captured_request_id IS NULL OR captured_actor IS NULL
       OR captured_reviewer IS NULL THEN
        RAISE EXCEPTION 'controlled master data apply evidence is incomplete';
    END IF;

    PERFORM pg_advisory_xact_lock(
        hashtextextended(captured_type || ':' || captured_key,0));
    SELECT COALESCE(max(revision_no),0)+1 INTO captured_revision
    FROM platform.master_data_revision
    WHERE entity_type=captured_type AND entity_key=captured_key;

    INSERT INTO platform.master_data_revision(
      entity_type,entity_key,revision_no,operation_code,source_relation,source_key,
      governance_state,snapshot,changed_by,change_request_id,reviewed_by,review_basis)
    VALUES(captured_type,captured_key,captured_revision,TG_OP,captured_source,
      captured_source_key,
      CASE WHEN TG_OP='DELETE' THEN 'RETIRED' ELSE 'ACTIVE' END,
      source_snapshot,captured_actor,captured_request_id,captured_reviewer,
      captured_review_basis);
    RETURN CASE WHEN TG_OP='DELETE' THEN OLD ELSE NEW END;
END;
$function$;

ALTER FUNCTION platform.capture_master_data_revision()
    OWNER TO qiqihar_migration_owner;
REVOKE ALL ON FUNCTION platform.capture_master_data_revision()
FROM PUBLIC,qiqihar_enterprise_runtime,qiqihar_master_data_applicant,
    qiqihar_master_data_reviewer,qiqihar_master_data_applier;

CREATE OR REPLACE FUNCTION registry.delete_formal_sample_point(
    deletion_sample_point_id uuid,
    expected_sample_point_version bigint,
    expected_region_code varchar,
    deletion_actor_subject_id varchar)
RETURNS varchar
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path=pg_catalog,registry
AS $function$
DECLARE
    point_row registry.sample_point%ROWTYPE;
    actor_work_unit_code varchar;
    deletion_event_id uuid;
    deletion_occurred_at timestamptz;
    deletion_detail jsonb;
BEGIN
    SELECT * INTO point_row
    FROM registry.sample_point
    WHERE sample_point_id=deletion_sample_point_id
      AND kind_code IN ('SURVEY_SITE','LOGISTICS_NODE')
      AND deletion_state='ACTIVE'
    FOR UPDATE;
    IF NOT FOUND THEN
        RETURN 'NOT_FOUND';
    END IF;
    IF point_row.version IS DISTINCT FROM expected_sample_point_version THEN
        RETURN 'VERSION_CONFLICT';
    END IF;
    IF point_row.region_code IS DISTINCT FROM expected_region_code THEN
        RETURN 'REGION_CONFLICT';
    END IF;

    SELECT security_user.work_unit_code INTO actor_work_unit_code
    FROM platform.security_user security_user
    JOIN platform.work_unit work_unit
      ON work_unit.code=security_user.work_unit_code AND work_unit.active
    JOIN platform.security_user_role user_role
      ON user_role.subject_id=security_user.subject_id
     AND CURRENT_TIMESTAMP>=user_role.valid_from
     AND (user_role.valid_until IS NULL OR CURRENT_TIMESTAMP<user_role.valid_until)
     AND (user_role.review_due_at IS NULL OR CURRENT_TIMESTAMP<user_role.review_due_at)
    JOIN platform.access_role access_role
      ON access_role.code=user_role.role_code AND access_role.active
    JOIN platform.access_role_permission role_permission
      ON role_permission.role_code=access_role.code
     AND role_permission.permission_code='FORMAL_SAMPLE_DELETE'
    JOIN platform.access_permission permission
      ON permission.code=role_permission.permission_code AND permission.active
    WHERE security_user.subject_id=deletion_actor_subject_id
      AND security_user.enabled
      AND security_user.account_status='ACTIVE'
      AND security_user.employment_status='ACTIVE'
      AND (security_user.termination_effective_at IS NULL
           OR security_user.termination_effective_at>CURRENT_TIMESTAMP)
    LIMIT 1;
    IF NOT FOUND THEN
        RETURN 'ACCESS_DENIED';
    END IF;
    IF NOT EXISTS(
        WITH RECURSIVE unit_authorized(region_code) AS (
            SELECT scope.region_code
            FROM platform.work_unit_region_scope scope
            WHERE scope.work_unit_code=actor_work_unit_code
            UNION
            SELECT child.code
            FROM platform.region child
            JOIN unit_authorized parent ON parent.region_code=child.parent_code
        ), assigned(region_code) AS (
            SELECT scope.region_code
            FROM platform.security_user_region_scope scope
            JOIN unit_authorized ON unit_authorized.region_code=scope.region_code
            WHERE scope.subject_id=deletion_actor_subject_id
              AND CURRENT_TIMESTAMP>=scope.valid_from
              AND (scope.valid_until IS NULL OR CURRENT_TIMESTAMP<scope.valid_until)
              AND (scope.review_due_at IS NULL OR CURRENT_TIMESTAMP<scope.review_due_at)
        ), covered(region_code) AS (
            SELECT region_code FROM assigned
            UNION
            SELECT child.code
            FROM platform.region child
            JOIN covered parent ON parent.region_code=child.parent_code
        )
        SELECT 1 FROM covered WHERE region_code=point_row.region_code
    ) THEN
        RETURN 'ACCESS_REGION_DENIED';
    END IF;

    deletion_event_id := gen_random_uuid();
    deletion_occurred_at := clock_timestamp();
    deletion_detail := jsonb_build_object(
        'regionCode',point_row.region_code,
        'regionCodes',jsonb_build_array(point_row.region_code),
        'deletionMode','PHYSICAL');

    PERFORM set_config(
        'application.formal_sample_delete_id', deletion_sample_point_id::text, true);
    DELETE FROM registry.sample_network_membership
    WHERE sample_point_id=deletion_sample_point_id;
    DELETE FROM platform.formal_sample_observation
    WHERE sample_point_id=deletion_sample_point_id;
    DELETE FROM market.market_inventory_governance
    WHERE sample_point_id=deletion_sample_point_id;
    DELETE FROM market.sample_point_inventory_contract
    WHERE sample_point_id=deletion_sample_point_id;
    DELETE FROM logistics.route_event
    WHERE sample_point_id=deletion_sample_point_id;
    UPDATE logistics.logistics_node SET sample_point_id=NULL
    WHERE sample_point_id=deletion_sample_point_id;
    DELETE FROM market.market_record
    WHERE sample_point_id=deletion_sample_point_id;
    DELETE FROM production.production_record
    WHERE sample_point_id=deletion_sample_point_id;
    DELETE FROM registry.sample_point
    WHERE sample_point_id=deletion_sample_point_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'formal sample point disappeared during governed deletion';
    END IF;
    PERFORM set_config('application.formal_sample_delete_id', '', true);

    INSERT INTO platform.business_audit_event(
        event_id,aggregate_type,aggregate_id,action_code,actor_subject_id,
        work_unit_code,occurred_at,detail)
    VALUES(deletion_event_id,'FORMAL_SAMPLE_POINT',deletion_sample_point_id::text,
        'FORMAL_SAMPLE_POINT_DELETED',deletion_actor_subject_id,
        actor_work_unit_code,deletion_occurred_at,deletion_detail);
    INSERT INTO platform.business_event_outbox(
        event_id,aggregate_type,aggregate_id,action_code,actor_subject_id,
        work_unit_code,region_codes,product_code,occurred_at,detail)
    VALUES(deletion_event_id,'FORMAL_SAMPLE_POINT',deletion_sample_point_id::text,
        'FORMAL_SAMPLE_POINT_DELETED',deletion_actor_subject_id,
        actor_work_unit_code,ARRAY[point_row.region_code]::varchar[],NULL,
        deletion_occurred_at,deletion_detail);
    RETURN 'DELETED';
END;
$function$;

ALTER FUNCTION registry.delete_formal_sample_point(uuid,bigint,varchar,varchar)
    OWNER TO qiqihar_migration_owner;
GRANT DELETE ON registry.sample_network_membership TO qiqihar_migration_owner;
REVOKE ALL ON FUNCTION registry.delete_formal_sample_point(uuid,bigint,varchar,varchar) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION registry.delete_formal_sample_point(uuid,bigint,varchar,varchar)
TO qiqihar_enterprise_runtime,CURRENT_USER;

COMMENT ON FUNCTION registry.delete_formal_sample_point(uuid,bigint,varchar,varchar) IS
    'Physically deletes one authorized formal sample and all business facts and active relationships in the same transaction, retaining only its minimal deletion audit and outbox event.';
