-- DEF-099: expose real LOGIN identities for each database duty while keeping the
-- functional roles NOLOGIN and actor-free at every public governance entry.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='qiqihar_master_data_applicant_login') THEN
        CREATE ROLE qiqihar_master_data_applicant_login LOGIN NOINHERIT;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='qiqihar_master_data_reviewer_login') THEN
        CREATE ROLE qiqihar_master_data_reviewer_login LOGIN NOINHERIT;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='qiqihar_master_data_applier_login') THEN
        CREATE ROLE qiqihar_master_data_applier_login LOGIN NOINHERIT;
    END IF;
END;
$$;

ALTER ROLE qiqihar_master_data_applicant_login LOGIN NOINHERIT;
ALTER ROLE qiqihar_master_data_reviewer_login LOGIN NOINHERIT;
ALTER ROLE qiqihar_master_data_applier_login LOGIN NOINHERIT;
GRANT qiqihar_master_data_applicant TO qiqihar_master_data_applicant_login;
GRANT qiqihar_master_data_reviewer TO qiqihar_master_data_reviewer_login;
GRANT qiqihar_master_data_applier TO qiqihar_master_data_applier_login;

ALTER TABLE registry.sample_subject_resolution_batch OWNER TO qiqihar_migration_owner;
ALTER TABLE registry.sample_subject_resolution_item OWNER TO qiqihar_migration_owner;
ALTER TABLE registry.sample_subject_resolution_revision OWNER TO qiqihar_migration_owner;
ALTER TABLE registry.sample_subject_resolution_audit OWNER TO qiqihar_migration_owner;
ALTER VIEW registry.current_sample_subject_resolution OWNER TO qiqihar_migration_owner;

REVOKE ALL ON TABLE
    registry.sample_subject_resolution_batch,
    registry.sample_subject_resolution_item,
    registry.sample_subject_resolution_revision,
    registry.sample_subject_resolution_audit
FROM PUBLIC, qiqihar_enterprise_runtime, qiqihar_master_data_applicant,
    qiqihar_master_data_reviewer, qiqihar_master_data_applier;
REVOKE ALL ON TABLE registry.current_sample_subject_resolution
FROM PUBLIC, qiqihar_enterprise_runtime, qiqihar_master_data_applicant,
    qiqihar_master_data_reviewer, qiqihar_master_data_applier;
GRANT SELECT ON TABLE registry.current_sample_subject_resolution
TO qiqihar_enterprise_runtime;
GRANT SELECT ON TABLE
    registry.sample_subject_resolution_batch,
    registry.sample_subject_resolution_item,
    registry.sample_subject_resolution_revision,
    registry.sample_subject_resolution_audit,
    registry.current_sample_subject_resolution
TO CURRENT_USER;

GRANT USAGE ON SCHEMA production,market TO qiqihar_migration_owner;
GRANT SELECT ON TABLE
    production.production_record,
    production.production_record_submission_metadata,
    market.market_record,
    market.market_record_core_value
TO qiqihar_migration_owner;

REVOKE ALL ON FUNCTION registry.apply_sample_subject_resolution(uuid,varchar),
    registry.rollback_sample_subject_resolution(uuid,varchar)
FROM PUBLIC, qiqihar_enterprise_runtime, qiqihar_master_data_applicant,
    qiqihar_master_data_reviewer, qiqihar_master_data_applier;
ALTER FUNCTION registry.apply_sample_subject_resolution(uuid,varchar)
OWNER TO qiqihar_migration_owner;
ALTER FUNCTION registry.rollback_sample_subject_resolution(uuid,varchar)
OWNER TO qiqihar_migration_owner;
ALTER FUNCTION registry.apply_sample_subject_resolution(uuid,varchar)
SET search_path = pg_catalog,registry,production,market;
ALTER FUNCTION registry.rollback_sample_subject_resolution(uuid,varchar)
SET search_path = pg_catalog,registry,production,market;

CREATE FUNCTION registry.apply_sample_subject_resolution(p_batch_id uuid)
RETURNS varchar
LANGUAGE sql
SECURITY DEFINER
SET search_path = pg_catalog,registry,production,market
AS $$
    SELECT registry.apply_sample_subject_resolution(p_batch_id,session_user::varchar)
$$;
ALTER FUNCTION registry.apply_sample_subject_resolution(uuid)
OWNER TO qiqihar_migration_owner;

CREATE FUNCTION registry.rollback_sample_subject_resolution(p_batch_id uuid)
RETURNS varchar
LANGUAGE sql
SECURITY DEFINER
SET search_path = pg_catalog,registry,production,market
AS $$
    SELECT registry.rollback_sample_subject_resolution(p_batch_id,session_user::varchar)
$$;
ALTER FUNCTION registry.rollback_sample_subject_resolution(uuid)
OWNER TO qiqihar_migration_owner;

REVOKE ALL ON FUNCTION registry.apply_sample_subject_resolution(uuid),
    registry.rollback_sample_subject_resolution(uuid)
FROM PUBLIC, qiqihar_enterprise_runtime, qiqihar_master_data_applicant,
    qiqihar_master_data_reviewer;
GRANT EXECUTE ON FUNCTION registry.apply_sample_subject_resolution(uuid),
    registry.rollback_sample_subject_resolution(uuid)
TO qiqihar_master_data_applier;

-- The runtime entry accepts identifiers only. The database session identity is
-- the applicant and the fixed SECURITY DEFINER owner is the automatic reviewer,
-- so writable business facts can never be promoted into audit identities.
CREATE FUNCTION platform.register_approved_sample_subject(
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
      'created_by',session_user::varchar);
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

-- DEF-100: checkpoints have an explicit connection lifecycle. A browser
-- reconnects with its last event cursor under a fresh connection UUID; old
-- connection state stays auditable but is excluded from operational backlog.
ALTER TABLE platform.business_event_delivery_checkpoint
    ADD COLUMN lifecycle_status varchar(16) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN lease_expires_at timestamptz NOT NULL
        DEFAULT (clock_timestamp() + interval '2 minutes'),
    ADD COLUMN retired_at timestamptz,
    ADD COLUMN retirement_reason varchar(40),
    ADD COLUMN resume_sequence bigint;

CREATE TABLE platform.business_event_consumer_lifecycle_event (
    lifecycle_event_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    consumer_id varchar(180) NOT NULL,
    instance_id varchar(120) NOT NULL,
    lifecycle_status varchar(16) NOT NULL CHECK (lifecycle_status IN ('RETIRED','EXPIRED')),
    reason_code varchar(40) NOT NULL CHECK (reason_code IN (
        'CLIENT_COMPLETED','CLIENT_TIMEOUT','CLIENT_ERROR','AUTHORIZATION_REVOKED',
        'APPLICATION_STOP','DELIVERY_DISCONNECTED','STREAM_ENDED',
        'LEASE_EXPIRED','MIGRATION_RESTART')),
    resume_sequence bigint NOT NULL CHECK (resume_sequence >= 0),
    occurred_at timestamptz NOT NULL,
    database_actor varchar(120) NOT NULL
);

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
    ADD CONSTRAINT business_event_consumer_lifecycle_status_check
        CHECK (lifecycle_status IN ('ACTIVE','RETIRED','EXPIRED')),
    ADD CONSTRAINT business_event_consumer_retirement_check
        CHECK ((lifecycle_status='ACTIVE' AND retired_at IS NULL
                  AND retirement_reason IS NULL AND resume_sequence IS NULL)
            OR (lifecycle_status IN ('RETIRED','EXPIRED') AND retired_at IS NOT NULL
                  AND retirement_reason IS NOT NULL AND resume_sequence IS NOT NULL
                  AND resume_sequence>=initial_sequence)),
    ADD CONSTRAINT business_event_consumer_retirement_reason_check
        CHECK (retirement_reason IS NULL OR retirement_reason IN (
          'CLIENT_COMPLETED','CLIENT_TIMEOUT','CLIENT_ERROR','AUTHORIZATION_REVOKED',
          'APPLICATION_STOP','DELIVERY_DISCONNECTED','STREAM_ENDED',
          'LEASE_EXPIRED','MIGRATION_RESTART'));

ALTER TABLE platform.business_event_delivery_checkpoint OWNER TO qiqihar_migration_owner;
ALTER TABLE platform.business_event_consumer_lifecycle_event OWNER TO qiqihar_migration_owner;

CREATE FUNCTION platform.ensure_business_event_consumer(
    requested_consumer_id varchar,
    requested_instance_id varchar,
    requested_initial_sequence bigint)
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
    INSERT INTO platform.business_event_delivery_checkpoint(
      consumer_id,initial_sequence,last_observed_sequence,last_delivered_sequence,
      last_instance_id,lifecycle_status,lease_expires_at,created_at,updated_at)
    VALUES(requested_consumer_id,requested_initial_sequence,requested_initial_sequence,
      requested_initial_sequence,requested_instance_id,'ACTIVE',event_time+interval '2 minutes',
      event_time,event_time)
    ON CONFLICT(consumer_id) DO UPDATE SET
      last_observed_sequence=GREATEST(
        platform.business_event_delivery_checkpoint.last_observed_sequence,
        requested_initial_sequence),
      last_instance_id=requested_instance_id,
      lease_expires_at=event_time+interval '2 minutes',updated_at=event_time
    WHERE platform.business_event_delivery_checkpoint.lifecycle_status='ACTIVE';
    GET DIAGNOSTICS affected=ROW_COUNT;
    RETURN affected=1;
END;
$$;
ALTER FUNCTION platform.ensure_business_event_consumer(varchar,varchar,bigint)
OWNER TO qiqihar_migration_owner;

CREATE FUNCTION platform.retire_business_event_consumer(
    requested_consumer_id varchar,
    requested_instance_id varchar,
    requested_resume_sequence bigint,
    requested_reason varchar)
RETURNS boolean
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog,platform
AS $$
DECLARE
    event_time timestamptz := clock_timestamp();
    affected integer;
BEGIN
    IF requested_reason NOT IN (
      'CLIENT_COMPLETED','CLIENT_TIMEOUT','CLIENT_ERROR','AUTHORIZATION_REVOKED',
      'APPLICATION_STOP','DELIVERY_DISCONNECTED','STREAM_ENDED') THEN
        RAISE EXCEPTION 'consumer retirement reason is invalid';
    END IF;
    WITH retired AS (
      UPDATE platform.business_event_delivery_checkpoint
      SET lifecycle_status='RETIRED',lease_expires_at=event_time,
          retired_at=event_time,retirement_reason=requested_reason,
          resume_sequence=GREATEST(initial_sequence,requested_resume_sequence),updated_at=event_time
      WHERE consumer_id=requested_consumer_id AND lifecycle_status='ACTIVE'
        AND last_instance_id=requested_instance_id
      RETURNING consumer_id,last_instance_id,resume_sequence,retired_at
    )
    INSERT INTO platform.business_event_consumer_lifecycle_event(
      consumer_id,instance_id,lifecycle_status,reason_code,resume_sequence,occurred_at,database_actor)
    SELECT consumer_id,last_instance_id,'RETIRED',requested_reason,resume_sequence,retired_at,
           session_user::varchar
    FROM retired;
    GET DIAGNOSTICS affected=ROW_COUNT;
    RETURN affected=1;
END;
$$;
ALTER FUNCTION platform.retire_business_event_consumer(varchar,varchar,bigint,varchar)
OWNER TO qiqihar_migration_owner;

CREATE FUNCTION platform.expire_business_event_consumers()
RETURNS integer
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog,platform
AS $$
DECLARE
    event_time timestamptz := clock_timestamp();
    affected integer;
BEGIN
    WITH expired AS (
      UPDATE platform.business_event_delivery_checkpoint
      SET lifecycle_status='EXPIRED',retired_at=event_time,
          retirement_reason='LEASE_EXPIRED',resume_sequence=last_delivered_sequence,
          updated_at=event_time
      WHERE lifecycle_status='ACTIVE' AND lease_expires_at<=event_time
      RETURNING consumer_id,last_instance_id,resume_sequence,retired_at
    )
    INSERT INTO platform.business_event_consumer_lifecycle_event(
      consumer_id,instance_id,lifecycle_status,reason_code,resume_sequence,occurred_at,database_actor)
    SELECT consumer_id,last_instance_id,'EXPIRED','LEASE_EXPIRED',resume_sequence,retired_at,
           session_user::varchar
    FROM expired;
    GET DIAGNOSTICS affected=ROW_COUNT;
    RETURN affected;
END;
$$;
ALTER FUNCTION platform.expire_business_event_consumers()
OWNER TO qiqihar_migration_owner;

CREATE INDEX business_event_delivery_checkpoint_active_lease
    ON platform.business_event_delivery_checkpoint(lease_expires_at,consumer_id)
    WHERE lifecycle_status='ACTIVE';
CREATE INDEX business_event_consumer_lifecycle_event_consumer
    ON platform.business_event_consumer_lifecycle_event(consumer_id,lifecycle_event_id DESC);

DROP VIEW platform.business_event_delivery_backlog;
CREATE VIEW platform.business_event_delivery_backlog AS
SELECT checkpoint.consumer_id,
       count(*) FILTER (WHERE state.status_code IS NULL
         OR state.status_code IN ('IN_PROGRESS','RETRY_SCHEDULED')) AS pending_count,
       count(*) FILTER (WHERE state.status_code='RETRY_SCHEDULED') AS retry_scheduled_count,
       count(*) FILTER (WHERE state.status_code='IN_PROGRESS') AS in_progress_count,
       count(*) FILTER (WHERE state.status_code='QUARANTINED') AS quarantined_count,
       min(event.occurred_at) FILTER (WHERE state.status_code IS NULL
         OR state.status_code IN ('IN_PROGRESS','RETRY_SCHEDULED')) AS oldest_pending_at,
       CASE WHEN min(event.occurred_at) FILTER (WHERE state.status_code IS NULL
         OR state.status_code IN ('IN_PROGRESS','RETRY_SCHEDULED')) IS NULL THEN 0
         ELSE GREATEST(0,floor(extract(epoch FROM clock_timestamp()
           - min(event.occurred_at) FILTER (WHERE state.status_code IS NULL
             OR state.status_code IN ('IN_PROGRESS','RETRY_SCHEDULED'))))::bigint)
       END AS oldest_pending_age_seconds,
       checkpoint.poll_next_retry_at,
       checkpoint.consecutive_poll_failures,
       checkpoint.updated_at AS checkpoint_updated_at,
       checkpoint.lease_expires_at
FROM platform.business_event_delivery_checkpoint checkpoint
LEFT JOIN platform.business_event_outbox event
  ON event.event_sequence > checkpoint.initial_sequence
LEFT JOIN platform.business_event_delivery_state state
  ON state.consumer_id=checkpoint.consumer_id AND state.event_id=event.event_id
WHERE checkpoint.lifecycle_status='ACTIVE'
  AND checkpoint.lease_expires_at>clock_timestamp()
GROUP BY checkpoint.consumer_id,checkpoint.poll_next_retry_at,
         checkpoint.consecutive_poll_failures,checkpoint.updated_at,
         checkpoint.lease_expires_at;

ALTER VIEW platform.business_event_delivery_backlog OWNER TO qiqihar_migration_owner;
REVOKE ALL ON TABLE platform.business_event_delivery_checkpoint
FROM PUBLIC, qiqihar_enterprise_runtime, qiqihar_master_data_applicant,
    qiqihar_master_data_reviewer, qiqihar_master_data_applier;
REVOKE ALL ON TABLE platform.business_event_consumer_lifecycle_event
FROM PUBLIC, qiqihar_enterprise_runtime, qiqihar_master_data_applicant,
    qiqihar_master_data_reviewer, qiqihar_master_data_applier;
REVOKE ALL ON SEQUENCE platform.business_event_consumer_lifecycle_event_lifecycle_event_id_seq
FROM PUBLIC, qiqihar_enterprise_runtime, qiqihar_master_data_applicant,
    qiqihar_master_data_reviewer, qiqihar_master_data_applier;
REVOKE ALL ON FUNCTION platform.ensure_business_event_consumer(varchar,varchar,bigint),
    platform.retire_business_event_consumer(varchar,varchar,bigint,varchar),
    platform.expire_business_event_consumers()
FROM PUBLIC, qiqihar_master_data_applicant,qiqihar_master_data_reviewer,
    qiqihar_master_data_applier;
GRANT SELECT ON TABLE platform.business_event_delivery_checkpoint,
    platform.business_event_consumer_lifecycle_event
TO qiqihar_enterprise_runtime;
GRANT UPDATE(last_observed_sequence,last_delivered_sequence,last_instance_id,
    delivered_count,quarantined_count,consecutive_poll_failures,poll_next_retry_at,
    last_poll_failure_code,last_poll_failure_message,updated_at)
ON platform.business_event_delivery_checkpoint TO qiqihar_enterprise_runtime;
GRANT EXECUTE ON FUNCTION platform.ensure_business_event_consumer(varchar,varchar,bigint),
    platform.retire_business_event_consumer(varchar,varchar,bigint,varchar),
    platform.expire_business_event_consumers()
TO qiqihar_enterprise_runtime;
GRANT SELECT ON TABLE platform.business_event_delivery_backlog
TO qiqihar_enterprise_runtime;
GRANT SELECT ON TABLE platform.business_event_consumer_lifecycle_event,
    platform.business_event_delivery_backlog
TO CURRENT_USER;

COMMENT ON TABLE platform.business_event_consumer_lifecycle_event IS
    'Auditable retirement and expiry history for connection-scoped event consumers.';
COMMENT ON VIEW platform.business_event_delivery_backlog IS
    'Operational backlog for active, unexpired connection consumers only; retired history remains in lifecycle tables.';
COMMENT ON FUNCTION platform.register_approved_sample_subject(varchar,varchar,uuid) IS
    'Runtime-only stable-subject entry; actors are bound to session_user and the fixed policy owner.';
COMMENT ON FUNCTION platform.ensure_business_event_consumer(varchar,varchar,bigint) IS
    'Owner-controlled checkpoint creation and lease renewal; retired consumer ids cannot be reused.';
COMMENT ON FUNCTION platform.retire_business_event_consumer(varchar,varchar,bigint,varchar) IS
    'Owner-controlled atomic consumer retirement and lifecycle audit append.';
COMMENT ON FUNCTION platform.expire_business_event_consumers() IS
    'Owner-controlled lease expiry using database time with an atomic lifecycle audit append.';
