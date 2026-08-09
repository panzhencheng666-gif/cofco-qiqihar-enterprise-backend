CREATE TABLE platform.business_event_outbox (
    event_id uuid PRIMARY KEY,
    event_sequence bigint GENERATED ALWAYS AS IDENTITY UNIQUE,
    aggregate_type varchar(60) NOT NULL,
    aggregate_id varchar(120) NOT NULL,
    action_code varchar(80) NOT NULL,
    actor_subject_id varchar(120) NOT NULL,
    work_unit_code varchar(80) NOT NULL,
    region_codes varchar(18)[] NOT NULL,
    product_code varchar(40),
    occurred_at timestamptz NOT NULL,
    detail jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (cardinality(region_codes) > 0)
);

CREATE INDEX business_event_outbox_region_scope
    ON platform.business_event_outbox USING gin(region_codes);
CREATE INDEX business_event_outbox_sequence
    ON platform.business_event_outbox(event_sequence);

CREATE TABLE platform.notification_read_receipt (
    event_id uuid NOT NULL REFERENCES platform.business_event_outbox(event_id) ON DELETE CASCADE,
    subject_id varchar(120) NOT NULL REFERENCES platform.security_user(subject_id) ON DELETE CASCADE,
    read_at timestamptz NOT NULL,
    PRIMARY KEY (event_id, subject_id)
);

COMMENT ON TABLE platform.business_event_outbox IS
    'Durable transaction-coupled business changes used by authorized notifications and realtime refresh.';
COMMENT ON TABLE platform.notification_read_receipt IS
    'Per-employee durable notification read state; event visibility remains region-authorized at read time.';
