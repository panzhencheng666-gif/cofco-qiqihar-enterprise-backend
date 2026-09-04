ALTER TABLE registry.sample_point
    ADD COLUMN retired_at timestamptz,
    ADD COLUMN retired_by varchar(120)
        REFERENCES platform.security_user(subject_id),
    ADD COLUMN retired_reason varchar(500);

ALTER TABLE registry.sample_point
    DROP CONSTRAINT sample_point_deletion_state_check,
    ADD CONSTRAINT sample_point_deletion_state_check CHECK (
        (deletion_state='ACTIVE'
          AND deleted_at IS NULL AND deleted_by IS NULL
          AND retired_at IS NULL AND retired_by IS NULL AND retired_reason IS NULL)
        OR (deletion_state='RETIRED'
          AND deleted_at IS NULL AND deleted_by IS NULL
          AND retired_at IS NOT NULL AND retired_by IS NOT NULL
          AND retired_reason IS NOT NULL AND btrim(retired_reason)<>'')
        OR (deletion_state='DELETED'
          AND deleted_at IS NOT NULL AND deleted_by IS NOT NULL
          AND retired_at IS NULL AND retired_by IS NULL AND retired_reason IS NULL));

CREATE INDEX sample_point_retirement_lookup
    ON registry.sample_point(deletion_state,retired_at,sample_point_id)
    WHERE deletion_state='RETIRED';

CREATE FUNCTION registry.retire_formal_sample_point(
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
     AND role_permission.permission_code='FORMAL_SAMPLE_DELETE'
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

    IF NOT EXISTS(
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

ALTER FUNCTION registry.retire_formal_sample_point(
    uuid,bigint,varchar,varchar,varchar,date)
    OWNER TO qiqihar_migration_owner;
REVOKE ALL ON FUNCTION registry.retire_formal_sample_point(
    uuid,bigint,varchar,varchar,varchar,date) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION registry.retire_formal_sample_point(
    uuid,bigint,varchar,varchar,varchar,date)
TO qiqihar_enterprise_runtime;

COMMENT ON COLUMN registry.sample_point.retired_at IS
    'Business retirement instant. Retired samples remain durable historical records.';
COMMENT ON COLUMN registry.sample_point.retired_reason IS
    'Human-entered reason for moving an active sample into historical display.';
