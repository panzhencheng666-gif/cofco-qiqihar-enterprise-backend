INSERT INTO platform.access_permission(code, name, active, sort_order)
VALUES('FORMAL_SAMPLE_DELETE', '删除正式样本', true, 3250);

-- Resolution history remains append-only. When its live target is physically
-- deleted, retain the target UUID as an explicit tombstone instead of retaining
-- a foreign-key reference to a non-existent sample.
ALTER TABLE registry.sample_subject_resolution_item
    ADD COLUMN deleted_target_sample_point_id uuid;
ALTER TABLE registry.sample_subject_resolution_revision
    ADD COLUMN deleted_target_sample_point_id uuid;

ALTER TABLE registry.sample_subject_resolution_item
    DROP CONSTRAINT sample_subject_resolution_item_target_sample_point_id_fkey,
    DROP CONSTRAINT sample_subject_resolution_item_check,
    ADD CONSTRAINT sample_subject_resolution_item_target_sample_point_id_fkey
        FOREIGN KEY(target_sample_point_id)
        REFERENCES registry.sample_point(sample_point_id) ON DELETE SET NULL,
    ADD CONSTRAINT sample_subject_resolution_item_check CHECK (
        (resolution_action='LINK' AND stable_subject_id IS NOT NULL
          AND btrim(stable_subject_id)<>''
          AND ((target_sample_point_id IS NOT NULL AND deleted_target_sample_point_id IS NULL)
            OR (target_sample_point_id IS NULL AND deleted_target_sample_point_id IS NOT NULL)))
        OR (resolution_action='VOID' AND stable_subject_id IS NULL
          AND target_sample_point_id IS NULL AND deleted_target_sample_point_id IS NULL));

ALTER TABLE registry.sample_subject_resolution_revision
    DROP CONSTRAINT sample_subject_resolution_revision_target_sample_point_id_fkey,
    DROP CONSTRAINT sample_subject_resolution_revision_check,
    ADD CONSTRAINT sample_subject_resolution_revision_target_sample_point_id_fkey
        FOREIGN KEY(target_sample_point_id)
        REFERENCES registry.sample_point(sample_point_id) ON DELETE SET NULL,
    ADD CONSTRAINT sample_subject_resolution_revision_check CHECK (
        (resolution_action='LINK' AND stable_subject_id IS NOT NULL
          AND ((target_sample_point_id IS NOT NULL AND deleted_target_sample_point_id IS NULL)
            OR (target_sample_point_id IS NULL AND deleted_target_sample_point_id IS NOT NULL)))
        OR (resolution_action IN ('VOID','ROLLBACK') AND stable_subject_id IS NULL
          AND target_sample_point_id IS NULL AND deleted_target_sample_point_id IS NULL));

CREATE FUNCTION registry.tombstone_deleted_resolution_target()
RETURNS trigger
LANGUAGE plpgsql
SET search_path=pg_catalog,registry
AS $function$
DECLARE
    deletion_id uuid;
BEGIN
    BEGIN
        deletion_id := NULLIF(
            current_setting('application.formal_sample_delete_id', true), '')::uuid;
    EXCEPTION WHEN invalid_text_representation THEN
        deletion_id := NULL;
    END;
    IF deletion_id IS NOT NULL
       AND current_user='qiqihar_migration_owner'
       AND OLD.target_sample_point_id=deletion_id
       AND NEW.target_sample_point_id IS NULL
       AND NEW.deleted_target_sample_point_id IS NULL THEN
        NEW.deleted_target_sample_point_id := OLD.target_sample_point_id;
    END IF;
    RETURN NEW;
END;
$function$;

CREATE TRIGGER a_resolution_item_deleted_target_tombstone
BEFORE UPDATE OF target_sample_point_id
ON registry.sample_subject_resolution_item
FOR EACH ROW EXECUTE FUNCTION registry.tombstone_deleted_resolution_target();

CREATE TRIGGER a_resolution_revision_deleted_target_tombstone
BEFORE UPDATE OF target_sample_point_id
ON registry.sample_subject_resolution_revision
FOR EACH ROW EXECUTE FUNCTION registry.tombstone_deleted_resolution_target();

CREATE OR REPLACE FUNCTION registry.reject_resolution_append_only_mutation()
RETURNS trigger LANGUAGE plpgsql AS $function$
DECLARE
    deletion_id uuid;
BEGIN
    BEGIN
        deletion_id := NULLIF(
            current_setting('application.formal_sample_delete_id', true), '')::uuid;
    EXCEPTION WHEN invalid_text_representation THEN
        deletion_id := NULL;
    END;
    IF TG_TABLE_NAME='sample_subject_resolution_revision'
       AND TG_OP='UPDATE'
       AND deletion_id IS NOT NULL
       AND current_user='qiqihar_migration_owner'
       AND (to_jsonb(OLD)->>'target_sample_point_id')::uuid=deletion_id
       AND to_jsonb(NEW)->>'target_sample_point_id' IS NULL
       AND (to_jsonb(NEW)->>'deleted_target_sample_point_id')::uuid=deletion_id
       AND (to_jsonb(NEW)-ARRAY['target_sample_point_id','deleted_target_sample_point_id'])
           =(to_jsonb(OLD)-ARRAY['target_sample_point_id','deleted_target_sample_point_id']) THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'resolution revisions and audit are append-only';
END;
$function$;

ALTER TABLE registry.sample_point_subject_identity
    DROP CONSTRAINT sample_point_subject_identity_sample_point_id_fkey,
    ADD CONSTRAINT sample_point_subject_identity_sample_point_id_fkey
        FOREIGN KEY(sample_point_id)
        REFERENCES registry.sample_point(sample_point_id) ON DELETE CASCADE;

CREATE FUNCTION registry.delete_formal_sample_point(
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
    identity_row registry.sample_point_subject_identity%ROWTYPE;
    actor_work_unit_code varchar;
    annual_observation_count bigint;
    deletion_event_id uuid;
    deletion_occurred_at timestamptz;
    deletion_detail jsonb;
BEGIN
    SELECT * INTO point_row
    FROM registry.sample_point
    WHERE sample_point_id=deletion_sample_point_id
      AND kind_code='SURVEY_SITE'
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
    IF EXISTS(
        SELECT 1 FROM registry.sample_network_membership
        WHERE sample_point_id=deletion_sample_point_id) THEN
        RETURN 'NETWORK_REFERENCED';
    END IF;
    IF EXISTS(
        SELECT 1
        FROM supply.source_release release
        WHERE (release.source_domain='PRODUCTION' AND EXISTS(
                 SELECT 1 FROM production.production_record record
                 WHERE record.sample_point_id=deletion_sample_point_id
                   AND record.record_id=release.source_record_id))
           OR (release.source_domain='MARKET' AND EXISTS(
                 SELECT 1 FROM market.market_record record
                 WHERE record.sample_point_id=deletion_sample_point_id
                   AND record.record_id=release.source_record_id))
           OR (release.source_domain='LOGISTICS' AND EXISTS(
                 SELECT 1 FROM logistics.route_event event
                 WHERE event.sample_point_id=deletion_sample_point_id
                   AND event.event_id::text=release.source_record_id))
        UNION ALL
        SELECT 1
        FROM platform.import_row_result result
        JOIN platform.import_job job ON job.import_job_id=result.import_job_id
        WHERE (job.domain_code='PRODUCTION' AND EXISTS(
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
                   AND event.event_id::text=result.business_record_id))
        UNION ALL
        SELECT 1
        FROM evidence.evidence_photo photo
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
                   AND event.event_id::text=photo.attached_record_id)))
    ) THEN
        RETURN 'HISTORICAL_REFERENCE';
    END IF;

    SELECT
      (SELECT count(*) FROM production.production_record record
       WHERE record.sample_point_id=deletion_sample_point_id)
      +(SELECT count(*) FROM market.market_record record
       WHERE record.sample_point_id=deletion_sample_point_id)
      +(SELECT count(*) FROM logistics.route_event event
       WHERE event.sample_point_id=deletion_sample_point_id)
    INTO annual_observation_count;

    PERFORM set_config(
        'application.formal_sample_delete_id', deletion_sample_point_id::text, true);

    FOR identity_row IN
        SELECT * FROM registry.sample_point_subject_identity
        WHERE sample_point_id=deletion_sample_point_id
        ORDER BY business_domain,subject_id
    LOOP
        PERFORM platform.govern_master_data_change(
            'SUBJECT',identity_row.business_domain || ':' || identity_row.subject_id,
            'DELETE',to_jsonb(identity_row),clock_timestamp(),
            'FORMAL_SAMPLE_DELETE_APPLICANT','FORMAL_SAMPLE_DELETE_REVIEWER',
            '正式样本物理删除同步退休稳定主体映射');
    END LOOP;

    DELETE FROM platform.formal_sample_observation
    WHERE sample_point_id=deletion_sample_point_id;
    DELETE FROM market.market_inventory_governance
    WHERE sample_point_id=deletion_sample_point_id;
    DELETE FROM market.sample_point_inventory_contract
    WHERE sample_point_id=deletion_sample_point_id;
    DELETE FROM logistics.route_event
    WHERE sample_point_id=deletion_sample_point_id;
    UPDATE logistics.logistics_node
    SET sample_point_id=NULL
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

    deletion_event_id := gen_random_uuid();
    deletion_occurred_at := clock_timestamp();
    deletion_detail := jsonb_build_object(
        'regionCode',point_row.region_code,
        'regionCodes',jsonb_build_array(point_row.region_code),
        'version',point_row.version,
        'annualObservationCount',annual_observation_count,
        'networkMembershipCount',0);
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

ALTER FUNCTION registry.tombstone_deleted_resolution_target()
    OWNER TO qiqihar_migration_owner;
ALTER FUNCTION registry.delete_formal_sample_point(uuid,bigint,varchar,varchar)
    OWNER TO qiqihar_migration_owner;

-- The security-definer function runs as the dedicated migration owner. Grant
-- only the table operations used by that function; runtime callers retain no
-- direct broad mutation path to the governed child tables.
GRANT USAGE ON SCHEMA platform,registry,production,market,logistics,supply,evidence
TO qiqihar_migration_owner;
GRANT SELECT ON registry.sample_point,registry.sample_network_membership,
    platform.security_user,platform.work_unit,platform.security_user_role,
    platform.access_role,platform.access_role_permission,platform.access_permission,
    platform.work_unit_region_scope,platform.security_user_region_scope,platform.region,
    platform.import_job,platform.import_row_result,supply.source_release,
    evidence.evidence_photo,production.production_record,market.market_record,
    logistics.route_event
TO qiqihar_migration_owner;
GRANT INSERT ON platform.business_audit_event,platform.business_event_outbox
TO qiqihar_migration_owner;
GRANT USAGE ON SEQUENCE platform.business_event_outbox_event_sequence_seq
TO qiqihar_migration_owner;
GRANT DELETE ON platform.formal_sample_observation,
    market.market_inventory_governance,
    market.sample_point_inventory_contract,
    logistics.route_event,
    market.market_record,
    production.production_record,
    registry.sample_point
TO qiqihar_migration_owner;
GRANT SELECT(sample_point_id) ON logistics.route_event,logistics.logistics_node
TO qiqihar_migration_owner;
GRANT UPDATE(sample_point_id) ON logistics.logistics_node
TO qiqihar_migration_owner;

REVOKE ALL ON FUNCTION registry.tombstone_deleted_resolution_target() FROM PUBLIC;
REVOKE DELETE ON registry.sample_point FROM qiqihar_enterprise_runtime;
REVOKE ALL ON FUNCTION registry.delete_formal_sample_point(uuid,bigint,varchar,varchar) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION registry.delete_formal_sample_point(uuid,bigint,varchar,varchar)
TO qiqihar_enterprise_runtime,CURRENT_USER;

COMMENT ON FUNCTION registry.delete_formal_sample_point(uuid,bigint,varchar,varchar) IS
    'Versioned physical deletion of one formal survey-site sample; blocks annual-network membership and clears mutable references atomically.';
