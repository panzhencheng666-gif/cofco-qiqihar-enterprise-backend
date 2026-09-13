-- Keep audited delete/retire behavior; authorize current responsibility instead of administrator-only permission.
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
     AND role_permission.permission_code IN ('FORMAL_SAMPLE_DELETE','BUSINESS_CREATE')
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
    IF NOT platform.account_has_administrator_role(deletion_actor_subject_id) AND NOT EXISTS(
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
    IF NOT platform.account_has_administrator_role(deletion_actor_subject_id)
       AND platform.region_responsible_subject(point_row.region_code) IS NOT NULL
       AND platform.region_responsible_subject(point_row.region_code) IS DISTINCT FROM deletion_actor_subject_id THEN
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

    DELETE FROM platform.import_job_photo job_photo
    USING evidence.evidence_photo photo
    WHERE job_photo.photo_id=photo.photo_id
      AND photo.state_code='ATTACHED'
      AND ((photo.attached_domain='PRODUCTION' AND EXISTS(
              SELECT 1 FROM production.production_record record
              WHERE record.sample_point_id=deletion_sample_point_id
                AND record.record_id=photo.attached_record_id))
        OR (photo.attached_domain='MARKET' AND EXISTS(
              SELECT 1 FROM market.market_record record
              WHERE record.sample_point_id=deletion_sample_point_id
                AND record.record_id=photo.attached_record_id))
        OR (photo.attached_domain='LOGISTICS' AND EXISTS(
              SELECT 1 FROM logistics.route_event event
              WHERE event.sample_point_id=deletion_sample_point_id
                AND event.event_id::text=photo.attached_record_id)));

    DELETE FROM platform.business_import_draft_evidence draft_evidence
    USING evidence.evidence_photo photo
    WHERE draft_evidence.photo_id=photo.photo_id
      AND photo.state_code='ATTACHED'
      AND ((photo.attached_domain='PRODUCTION' AND EXISTS(
              SELECT 1 FROM production.production_record record
              WHERE record.sample_point_id=deletion_sample_point_id
                AND record.record_id=photo.attached_record_id))
        OR (photo.attached_domain='MARKET' AND EXISTS(
              SELECT 1 FROM market.market_record record
              WHERE record.sample_point_id=deletion_sample_point_id
                AND record.record_id=photo.attached_record_id))
        OR (photo.attached_domain='LOGISTICS' AND EXISTS(
              SELECT 1 FROM logistics.route_event event
              WHERE event.sample_point_id=deletion_sample_point_id
                AND event.event_id::text=photo.attached_record_id)));

    UPDATE evidence.evidence_photo photo
    SET state_code='STAGED',attached_domain=NULL,
        attached_record_id=NULL,attached_region_code=NULL
    WHERE photo.state_code='ATTACHED'
      AND ((photo.attached_domain='PRODUCTION' AND EXISTS(
              SELECT 1 FROM production.production_record record
              WHERE record.sample_point_id=deletion_sample_point_id
                AND record.record_id=photo.attached_record_id))
        OR (photo.attached_domain='MARKET' AND EXISTS(
              SELECT 1 FROM market.market_record record
              WHERE record.sample_point_id=deletion_sample_point_id
                AND record.record_id=photo.attached_record_id))
        OR (photo.attached_domain='LOGISTICS' AND EXISTS(
              SELECT 1 FROM logistics.route_event event
              WHERE event.sample_point_id=deletion_sample_point_id
                AND event.event_id::text=photo.attached_record_id)));

    DELETE FROM platform.import_row_result result
    USING platform.import_job job
    WHERE job.import_job_id=result.import_job_id
      AND ((job.domain_code='PRODUCTION' AND EXISTS(
              SELECT 1 FROM production.production_record record
              WHERE record.sample_point_id=deletion_sample_point_id
                AND record.record_id=result.business_record_id))
        OR (job.domain_code='MARKET' AND EXISTS(
              SELECT 1 FROM market.market_record record
              WHERE record.sample_point_id=deletion_sample_point_id
                AND record.record_id=result.business_record_id))
        OR (job.domain_code='LOGISTICS' AND EXISTS(
              SELECT 1 FROM logistics.route_event event
              WHERE event.sample_point_id=deletion_sample_point_id
                AND event.event_id::text=result.business_record_id)));

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

CREATE OR REPLACE FUNCTION registry.retire_formal_sample_point(
    retirement_sample_point_id uuid,
    expected_sample_point_version bigint,
    expected_region_code varchar,
    retirement_actor_subject_id varchar,
    retirement_reason varchar,
    retirement_date date)
RETURNS varchar
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path=pg_catalog,registry
AS $function$
DECLARE
    point_row registry.sample_point%ROWTYPE;
    actor_work_unit_code varchar;
BEGIN
    SELECT * INTO point_row
    FROM registry.sample_point
    WHERE sample_point_id=retirement_sample_point_id
      AND kind_code IN ('SURVEY_SITE','LOGISTICS_NODE')
    FOR UPDATE;
    IF NOT FOUND OR point_row.deletion_state<>'ACTIVE' THEN
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
     AND role_permission.permission_code IN ('FORMAL_SAMPLE_DELETE','BUSINESS_CREATE')
    JOIN platform.access_permission permission
      ON permission.code=role_permission.permission_code AND permission.active
    WHERE security_user.subject_id=retirement_actor_subject_id
      AND security_user.enabled
      AND security_user.account_status='ACTIVE'
      AND security_user.employment_status='ACTIVE'
      AND (security_user.termination_effective_at IS NULL
        OR security_user.termination_effective_at>CURRENT_TIMESTAMP)
    LIMIT 1;
    IF NOT FOUND THEN
        RETURN 'ACCESS_DENIED';
    END IF;

    IF NOT platform.account_has_administrator_role(retirement_actor_subject_id) AND NOT EXISTS(
        WITH RECURSIVE unit_authorized(region_code) AS (
            SELECT scope.region_code
            FROM platform.work_unit_region_scope scope
            WHERE scope.work_unit_code=actor_work_unit_code
            UNION
            SELECT child.code FROM platform.region child
            JOIN unit_authorized parent ON parent.region_code=child.parent_code
        ), assigned(region_code) AS (
            SELECT scope.region_code
            FROM platform.security_user_region_scope scope
            JOIN unit_authorized ON unit_authorized.region_code=scope.region_code
            WHERE scope.subject_id=retirement_actor_subject_id
              AND CURRENT_TIMESTAMP>=scope.valid_from
              AND (scope.valid_until IS NULL OR CURRENT_TIMESTAMP<scope.valid_until)
              AND (scope.review_due_at IS NULL OR CURRENT_TIMESTAMP<scope.review_due_at)
        ), covered(region_code) AS (
            SELECT region_code FROM assigned
            UNION
            SELECT child.code FROM platform.region child
            JOIN covered parent ON parent.region_code=child.parent_code
        )
        SELECT 1 FROM covered WHERE region_code=point_row.region_code
    ) THEN
        RETURN 'ACCESS_REGION_DENIED';
    END IF;
    IF NOT platform.account_has_administrator_role(retirement_actor_subject_id)
       AND platform.region_responsible_subject(point_row.region_code) IS NOT NULL
       AND platform.region_responsible_subject(point_row.region_code) IS DISTINCT FROM retirement_actor_subject_id THEN
        RETURN 'ACCESS_REGION_DENIED';
    END IF;


    UPDATE registry.sample_point
    SET deletion_state='RETIRED',effective_to=retirement_date,
        retired_at=CURRENT_TIMESTAMP,retired_by=retirement_actor_subject_id,
        retired_reason=retirement_reason,version=version+1,
        updated_by=retirement_actor_subject_id,updated_at=CURRENT_TIMESTAMP
    WHERE sample_point_id=retirement_sample_point_id;

    UPDATE registry.sample_network_membership
    SET status_code='REMOVED',decision_reason=retirement_reason,
        decided_by=retirement_actor_subject_id,decided_at=CURRENT_TIMESTAMP,
        version=version+1
    WHERE sample_point_id=retirement_sample_point_id
      AND network_year>=EXTRACT(YEAR FROM retirement_date)
      AND status_code<>'REMOVED';
    RETURN 'RETIRED';
END;
$function$;
