CREATE TABLE platform.business_event_delivery_checkpoint (
    consumer_id varchar(180) PRIMARY KEY,
    initial_sequence bigint NOT NULL CHECK (initial_sequence >= 0),
    last_observed_sequence bigint NOT NULL CHECK (last_observed_sequence >= initial_sequence),
    last_delivered_sequence bigint NOT NULL CHECK (last_delivered_sequence >= initial_sequence),
    last_instance_id varchar(120) NOT NULL,
    delivered_count bigint NOT NULL DEFAULT 0 CHECK (delivered_count >= 0),
    quarantined_count bigint NOT NULL DEFAULT 0 CHECK (quarantined_count >= 0),
    consecutive_poll_failures integer NOT NULL DEFAULT 0 CHECK (consecutive_poll_failures >= 0),
    poll_next_retry_at timestamptz,
    last_poll_failure_code varchar(120),
    last_poll_failure_message varchar(1000),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);

CREATE TABLE platform.business_event_delivery_state (
    consumer_id varchar(180) NOT NULL
        REFERENCES platform.business_event_delivery_checkpoint(consumer_id) ON DELETE CASCADE,
    event_id uuid NOT NULL
        REFERENCES platform.business_event_outbox(event_id) ON DELETE CASCADE,
    event_sequence bigint NOT NULL,
    status_code varchar(32) NOT NULL
        CHECK (status_code IN ('IN_PROGRESS','RETRY_SCHEDULED','DELIVERED','QUARANTINED')),
    attempt_count integer NOT NULL CHECK (attempt_count > 0),
    lease_owner varchar(120),
    lease_token uuid,
    lease_until timestamptz,
    next_retry_at timestamptz,
    delivered_at timestamptz,
    quarantined_at timestamptz,
    last_failure_code varchar(120),
    last_failure_message varchar(1000),
    updated_at timestamptz NOT NULL,
    PRIMARY KEY (consumer_id,event_id),
    UNIQUE (consumer_id,event_sequence),
    CHECK ((status_code = 'IN_PROGRESS') =
        (lease_owner IS NOT NULL AND lease_token IS NOT NULL AND lease_until IS NOT NULL)),
    CHECK ((status_code = 'RETRY_SCHEDULED') = (next_retry_at IS NOT NULL)),
    CHECK ((status_code = 'DELIVERED') = (delivered_at IS NOT NULL)),
    CHECK ((status_code = 'QUARANTINED') = (quarantined_at IS NOT NULL))
);

CREATE INDEX business_event_delivery_state_retry
    ON platform.business_event_delivery_state(consumer_id,next_retry_at,event_sequence)
    WHERE status_code = 'RETRY_SCHEDULED';

CREATE TABLE platform.business_event_delivery_attempt (
    consumer_id varchar(180) NOT NULL,
    event_id uuid NOT NULL,
    event_sequence bigint NOT NULL,
    attempt_no integer NOT NULL CHECK (attempt_no > 0),
    instance_id varchar(120) NOT NULL,
    lease_token uuid NOT NULL,
    status_code varchar(32) NOT NULL
        CHECK (status_code IN ('IN_PROGRESS','DELIVERED','RETRY_SCHEDULED','QUARANTINED')),
    started_at timestamptz NOT NULL,
    completed_at timestamptz,
    next_retry_at timestamptz,
    failure_code varchar(120),
    failure_message varchar(1000),
    PRIMARY KEY (consumer_id,event_id,attempt_no),
    FOREIGN KEY (consumer_id,event_id)
        REFERENCES platform.business_event_delivery_state(consumer_id,event_id) ON DELETE CASCADE,
    CHECK ((status_code = 'IN_PROGRESS') = (completed_at IS NULL)),
    CHECK ((status_code = 'RETRY_SCHEDULED') = (next_retry_at IS NOT NULL))
);

CREATE UNIQUE INDEX business_event_delivery_once
    ON platform.business_event_delivery_attempt(consumer_id,event_id)
    WHERE status_code = 'DELIVERED';

CREATE TABLE platform.business_event_poll_attempt (
    poll_attempt_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    consumer_id varchar(180) NOT NULL
        REFERENCES platform.business_event_delivery_checkpoint(consumer_id) ON DELETE CASCADE,
    instance_id varchar(120) NOT NULL,
    after_sequence bigint NOT NULL CHECK (after_sequence >= 0),
    attempt_no integer NOT NULL CHECK (attempt_no > 0),
    status_code varchar(24) NOT NULL CHECK (status_code IN ('SUCCEEDED','RETRY_SCHEDULED')),
    attempted_at timestamptz NOT NULL,
    completed_at timestamptz NOT NULL,
    next_retry_at timestamptz,
    failure_code varchar(120),
    failure_message varchar(1000),
    CHECK ((status_code = 'RETRY_SCHEDULED') = (next_retry_at IS NOT NULL))
);

CREATE INDEX business_event_poll_attempt_consumer
    ON platform.business_event_poll_attempt(consumer_id,poll_attempt_id DESC);

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
       checkpoint.updated_at AS checkpoint_updated_at
FROM platform.business_event_delivery_checkpoint checkpoint
LEFT JOIN platform.business_event_outbox event
  ON event.event_sequence > checkpoint.initial_sequence
LEFT JOIN platform.business_event_delivery_state state
  ON state.consumer_id=checkpoint.consumer_id AND state.event_id=event.event_id
GROUP BY checkpoint.consumer_id,checkpoint.poll_next_retry_at,
         checkpoint.consecutive_poll_failures,checkpoint.updated_at;

COMMENT ON TABLE platform.business_event_delivery_checkpoint IS
    'Durable logical-consumer checkpoint shared by all service instances.';
COMMENT ON TABLE platform.business_event_delivery_state IS
    'Idempotent current delivery state and lease for each logical consumer and business event.';
COMMENT ON TABLE platform.business_event_delivery_attempt IS
    'Persistent delivery attempt history, including bounded retry and quarantine outcomes.';
COMMENT ON TABLE platform.business_event_poll_attempt IS
    'Persistent query attempt history so transient outbox query failures are observable and retried.';
COMMENT ON VIEW platform.business_event_delivery_backlog IS
    'Operational backlog, retry, quarantine and oldest pending age projection per logical consumer.';
