-- DEF-103: V112 made the operational backlog connection-aware but joined every
-- outbox event after a consumer cursor. Bind each live connection to a current
-- enterprise authorization subject and expose the resulting view only to a
-- dedicated operations role, never to the application runtime.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='qiqihar_event_operations_monitor') THEN
        CREATE ROLE qiqihar_event_operations_monitor NOLOGIN NOINHERIT;
    END IF;
END;
$$;

ALTER ROLE qiqihar_event_operations_monitor NOLOGIN NOINHERIT;
GRANT USAGE ON SCHEMA platform TO qiqihar_event_operations_monitor;

ALTER TABLE platform.business_event_delivery_checkpoint
    ADD COLUMN authorization_subject_id varchar(120)
        REFERENCES platform.security_user(subject_id);

-- A rolling application could still hold a V112 lease without a subject. End
-- those leases atomically so V114 never guesses their authorization context.
WITH expired AS (
    UPDATE platform.business_event_delivery_checkpoint
    SET lifecycle_status='EXPIRED',lease_expires_at=clock_timestamp(),
        retired_at=clock_timestamp(),retirement_reason='MIGRATION_RESTART',
        resume_sequence=last_delivered_sequence,updated_at=clock_timestamp()
    WHERE lifecycle_status='ACTIVE'
    RETURNING consumer_id,last_instance_id,resume_sequence,retired_at
)
INSERT INTO platform.business_event_consumer_lifecycle_event(
    consumer_id,instance_id,lifecycle_status,reason_code,resume_sequence,occurred_at,database_actor)
SELECT consumer_id,last_instance_id,'EXPIRED','MIGRATION_RESTART',resume_sequence,retired_at,
       current_user::varchar
FROM expired;

ALTER TABLE platform.business_event_delivery_checkpoint
    ADD CONSTRAINT business_event_consumer_active_authorization_check
        CHECK (lifecycle_status<>'ACTIVE' OR authorization_subject_id IS NOT NULL);

CREATE FUNCTION platform.ensure_business_event_consumer(
    requested_consumer_id varchar,
    requested_instance_id varchar,
    requested_initial_sequence bigint,
    requested_authorization_subject_id varchar)
RETURNS boolean
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog,platform
AS $$
DECLARE
    event_time timestamptz := clock_timestamp();
    affected integer;
BEGIN
    IF requested_consumer_id IS NULL OR btrim(requested_consumer_id)=''
       OR length(requested_consumer_id)>180 THEN
        RAISE EXCEPTION 'consumer id is invalid';
    END IF;
    IF requested_instance_id IS NULL OR btrim(requested_instance_id)=''
       OR length(requested_instance_id)>120 THEN
        RAISE EXCEPTION 'consumer instance id is invalid';
    END IF;
    IF requested_initial_sequence<0 THEN
        RAISE EXCEPTION 'initial sequence is invalid';
    END IF;
    IF requested_authorization_subject_id IS NULL
       OR btrim(requested_authorization_subject_id)=''
       OR length(requested_authorization_subject_id)>120 THEN
        RAISE EXCEPTION 'authorization subject is invalid';
    END IF;
    IF NOT EXISTS (
        SELECT 1
        FROM platform.security_user security_user
        JOIN platform.work_unit work_unit
          ON work_unit.code=security_user.work_unit_code AND work_unit.active
        WHERE security_user.subject_id=requested_authorization_subject_id
          AND security_user.enabled
          AND security_user.account_status='ACTIVE'
          AND security_user.employment_status='ACTIVE'
          AND (security_user.termination_effective_at IS NULL
               OR security_user.termination_effective_at>event_time)
          AND EXISTS (
              SELECT 1
              FROM platform.security_user_role user_role
              JOIN platform.access_role access_role
                ON access_role.code=user_role.role_code AND access_role.active
              JOIN platform.access_role_permission role_permission
                ON role_permission.role_code=access_role.code
               AND role_permission.permission_code='BUSINESS_READ'
              JOIN platform.access_permission permission
                ON permission.code=role_permission.permission_code AND permission.active
              WHERE user_role.subject_id=security_user.subject_id
                AND event_time>=user_role.valid_from
                AND (user_role.valid_until IS NULL OR event_time<user_role.valid_until)
                AND (user_role.review_due_at IS NULL OR event_time<user_role.review_due_at)
          )
    ) THEN
        RAISE EXCEPTION 'authorization subject cannot read business events';
    END IF;

    INSERT INTO platform.business_event_delivery_checkpoint(
      consumer_id,initial_sequence,last_observed_sequence,last_delivered_sequence,
      last_instance_id,lifecycle_status,lease_expires_at,authorization_subject_id,
      created_at,updated_at)
    VALUES(requested_consumer_id,requested_initial_sequence,requested_initial_sequence,
      requested_initial_sequence,requested_instance_id,'ACTIVE',event_time+interval '2 minutes',
      requested_authorization_subject_id,event_time,event_time)
    ON CONFLICT(consumer_id) DO UPDATE SET
      last_observed_sequence=GREATEST(
        platform.business_event_delivery_checkpoint.last_observed_sequence,
        requested_initial_sequence),
      last_instance_id=requested_instance_id,
      lease_expires_at=event_time+interval '2 minutes',updated_at=event_time
    WHERE platform.business_event_delivery_checkpoint.lifecycle_status='ACTIVE'
      AND platform.business_event_delivery_checkpoint.authorization_subject_id
          =requested_authorization_subject_id;
    GET DIAGNOSTICS affected=ROW_COUNT;
    RETURN affected=1;
END;
$$;

ALTER FUNCTION platform.ensure_business_event_consumer(varchar,varchar,bigint,varchar)
OWNER TO qiqihar_migration_owner;

DROP VIEW platform.business_event_delivery_backlog;
CREATE VIEW platform.business_event_delivery_backlog
WITH (security_barrier=true) AS
WITH RECURSIVE evaluation_time(evaluated_at) AS (
    VALUES(clock_timestamp())
), eligible_consumer AS (
    SELECT checkpoint.consumer_id,checkpoint.initial_sequence,
           checkpoint.authorization_subject_id,security_user.work_unit_code,
           checkpoint.poll_next_retry_at,checkpoint.consecutive_poll_failures,
           checkpoint.updated_at,checkpoint.lease_expires_at,evaluation_time.evaluated_at
    FROM platform.business_event_delivery_checkpoint checkpoint
    CROSS JOIN evaluation_time
    JOIN platform.security_user security_user
      ON security_user.subject_id=checkpoint.authorization_subject_id
     AND security_user.enabled
     AND security_user.account_status='ACTIVE'
     AND security_user.employment_status='ACTIVE'
     AND (security_user.termination_effective_at IS NULL
          OR security_user.termination_effective_at>evaluation_time.evaluated_at)
    JOIN platform.work_unit work_unit
      ON work_unit.code=security_user.work_unit_code AND work_unit.active
    WHERE checkpoint.lifecycle_status='ACTIVE'
      AND checkpoint.lease_expires_at>evaluation_time.evaluated_at
      AND EXISTS (
          SELECT 1
          FROM platform.security_user_role user_role
          JOIN platform.access_role access_role
            ON access_role.code=user_role.role_code AND access_role.active
          JOIN platform.access_role_permission role_permission
            ON role_permission.role_code=access_role.code
           AND role_permission.permission_code='BUSINESS_READ'
          JOIN platform.access_permission permission
            ON permission.code=role_permission.permission_code AND permission.active
          WHERE user_role.subject_id=security_user.subject_id
            AND evaluation_time.evaluated_at>=user_role.valid_from
            AND (user_role.valid_until IS NULL
                 OR evaluation_time.evaluated_at<user_role.valid_until)
            AND (user_role.review_due_at IS NULL
                 OR evaluation_time.evaluated_at<user_role.review_due_at)
      )
), assigned_scope(consumer_id,region_code) AS (
    SELECT DISTINCT consumer.consumer_id,user_scope.region_code
    FROM eligible_consumer consumer
    JOIN platform.security_user_region_scope user_scope
      ON user_scope.subject_id=consumer.authorization_subject_id
     AND consumer.evaluated_at>=user_scope.valid_from
     AND (user_scope.valid_until IS NULL OR consumer.evaluated_at<user_scope.valid_until)
     AND (user_scope.review_due_at IS NULL OR consumer.evaluated_at<user_scope.review_due_at)
    JOIN platform.work_unit_region_scope work_unit_scope
      ON work_unit_scope.work_unit_code=consumer.work_unit_code
     AND work_unit_scope.region_code=user_scope.region_code
), covered_scope(consumer_id,region_code) AS (
    SELECT consumer_id,region_code FROM assigned_scope
    UNION
    SELECT covered.consumer_id,child.code
    FROM covered_scope covered
    JOIN platform.region child ON child.parent_code=covered.region_code
), authorized_event AS (
    SELECT consumer.consumer_id,event.event_id,event.occurred_at
    FROM eligible_consumer consumer
    JOIN platform.business_event_outbox event
      ON event.event_sequence>consumer.initial_sequence
     AND EXISTS (
         SELECT 1
         FROM covered_scope scope
         WHERE scope.consumer_id=consumer.consumer_id
           AND scope.region_code=ANY(event.region_codes)
     )
)
SELECT consumer.consumer_id,
       count(event.event_id) FILTER (WHERE state.status_code IS NULL
         OR state.status_code IN ('IN_PROGRESS','RETRY_SCHEDULED')) AS pending_count,
       count(event.event_id) FILTER (WHERE state.status_code='RETRY_SCHEDULED')
         AS retry_scheduled_count,
       count(event.event_id) FILTER (WHERE state.status_code='IN_PROGRESS')
         AS in_progress_count,
       count(event.event_id) FILTER (WHERE state.status_code='QUARANTINED')
         AS quarantined_count,
       min(event.occurred_at) FILTER (WHERE state.status_code IS NULL
         OR state.status_code IN ('IN_PROGRESS','RETRY_SCHEDULED')) AS oldest_pending_at,
       CASE WHEN min(event.occurred_at) FILTER (WHERE state.status_code IS NULL
         OR state.status_code IN ('IN_PROGRESS','RETRY_SCHEDULED')) IS NULL THEN 0
         ELSE GREATEST(0,floor(extract(epoch FROM consumer.evaluated_at
           - min(event.occurred_at) FILTER (WHERE state.status_code IS NULL
             OR state.status_code IN ('IN_PROGRESS','RETRY_SCHEDULED'))))::bigint)
       END AS oldest_pending_age_seconds,
       consumer.poll_next_retry_at,consumer.consecutive_poll_failures,
       consumer.updated_at AS checkpoint_updated_at,consumer.lease_expires_at
FROM eligible_consumer consumer
LEFT JOIN authorized_event event ON event.consumer_id=consumer.consumer_id
LEFT JOIN platform.business_event_delivery_state state
  ON state.consumer_id=consumer.consumer_id AND state.event_id=event.event_id
GROUP BY consumer.consumer_id,consumer.poll_next_retry_at,
         consumer.consecutive_poll_failures,consumer.updated_at,
         consumer.lease_expires_at,consumer.evaluated_at;

ALTER VIEW platform.business_event_delivery_backlog OWNER TO qiqihar_migration_owner;

REVOKE ALL ON FUNCTION platform.ensure_business_event_consumer(varchar,varchar,bigint)
FROM PUBLIC,qiqihar_enterprise_runtime,qiqihar_master_data_applicant,
    qiqihar_master_data_reviewer,qiqihar_master_data_applier;
REVOKE ALL ON FUNCTION platform.ensure_business_event_consumer(varchar,varchar,bigint,varchar)
FROM PUBLIC,qiqihar_master_data_applicant,qiqihar_master_data_reviewer,
    qiqihar_master_data_applier;
GRANT EXECUTE ON FUNCTION platform.ensure_business_event_consumer(
    varchar,varchar,bigint,varchar)
TO qiqihar_enterprise_runtime;

REVOKE ALL ON TABLE platform.business_event_delivery_backlog
FROM PUBLIC,qiqihar_enterprise_runtime,qiqihar_master_data_applicant,
    qiqihar_master_data_reviewer,qiqihar_master_data_applier;
GRANT SELECT ON TABLE platform.business_event_delivery_backlog
TO qiqihar_event_operations_monitor;

-- The runtime retains only the V112 operational update columns. It cannot set
-- or rebind authorization_subject_id directly; the owner function permits only
-- an active subject and treats the consumer-to-subject association as immutable.
REVOKE UPDATE(authorization_subject_id)
ON platform.business_event_delivery_checkpoint FROM qiqihar_enterprise_runtime;

COMMENT ON COLUMN platform.business_event_delivery_checkpoint.authorization_subject_id IS
    'Immutable authorization subject for a live connection; current effective grants determine operational scope.';
COMMENT ON VIEW platform.business_event_delivery_backlog IS
    'Operations-only backlog for active consumers and outbox events in each bound subject current effective region scope.';
COMMENT ON FUNCTION platform.ensure_business_event_consumer(varchar,varchar,bigint,varchar) IS
    'Owner-controlled checkpoint creation and renewal with an immutable, currently authorized enterprise subject.';
COMMENT ON ROLE qiqihar_event_operations_monitor IS
    'NOLOGIN operations role allowed to inspect authorization-filtered business event backlog metadata.';
