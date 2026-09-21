-- Enabled employees share business data without receiving identity governance events.
-- CREATE OR REPLACE preserves the registrar-only execution privileges established by V115.
CREATE OR REPLACE FUNCTION platform.ensure_business_event_consumer(
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


CREATE OR REPLACE VIEW platform.business_event_delivery_backlog
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
     AND ((event.aggregate_type='MARKET_RECORD' AND event.action_code IN ('MARKET_RECORD_CREATED','MARKET_RECORD_SAVED','MARKET_RECORD_VOIDED','MARKET_RECORD_SUBMITTED','MARKET_RECORD_IMPORTED'))
 OR (event.aggregate_type='PRODUCTION_RECORD' AND event.action_code IN ('PRODUCTION_RECORD_CREATED','PRODUCTION_RECORD_SAVED','PRODUCTION_RECORD_VOIDED','PRODUCTION_RECORD_SUBMITTED','PRODUCTION_RECORD_IMPORTED'))
 OR (event.aggregate_type='LOGISTICS_RECORD' AND event.action_code IN ('LOGISTICS_RECORD_CREATED','LOGISTICS_RECORD_SAVED','LOGISTICS_RECORD_VOIDED','LOGISTICS_RECORD_SUBMITTED','LOGISTICS_RECORD_IMPORTED'))
 OR (event.aggregate_type='FORMAL_SAMPLE_OBSERVATION' AND event.action_code='FORMAL_SAMPLE_OBSERVATION_SAVED')
 OR (event.aggregate_type='FORMAL_SAMPLE_POINT' AND event.action_code IN ('FORMAL_SAMPLE_POINT_CREATED','FORMAL_SAMPLE_POINT_UPDATED','FORMAL_SAMPLE_POINT_RETIRED'))
 OR (event.aggregate_type='DESIGN_SAMPLE_POINT' AND event.action_code IN ('DESIGN_SAMPLE_POINT_CREATED','DESIGN_SAMPLE_POINT_UPDATED','DESIGN_SAMPLE_POINT_DELETED'))
 OR (event.aggregate_type='MARKET_OBJECT' AND event.action_code IN ('MARKET_OBJECT_CREATED','MARKET_OBJECT_UPDATED'))
 OR (event.aggregate_type='PRODUCTION_OBJECT' AND event.action_code IN ('PRODUCTION_OBJECT_CREATED','PRODUCTION_OBJECT_UPDATED'))
 OR (event.aggregate_type='REGIONAL_CROP_ANNUAL_STAT' AND event.action_code='REGIONAL_CROP_ANNUAL_STAT_UPSERTED')
 OR (event.aggregate_type='SUPPLY_DEMAND_BALANCE' AND event.action_code='SUPPLY_BALANCE_UPSERTED'))
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
