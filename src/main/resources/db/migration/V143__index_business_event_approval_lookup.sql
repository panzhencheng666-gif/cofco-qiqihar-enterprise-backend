CREATE INDEX business_event_outbox_approval_lookup
    ON platform.business_event_outbox(
        aggregate_type,
        aggregate_id,
        action_code,
        occurred_at DESC);

COMMENT ON INDEX platform.business_event_outbox_approval_lookup IS
    'Supports current approved business projections without rescanning the durable outbox for every joined fact row.';
