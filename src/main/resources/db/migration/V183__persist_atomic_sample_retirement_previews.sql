CREATE TABLE registry.formal_sample_retirement_batch (
    batch_id uuid PRIMARY KEY,
    snapshot jsonb NOT NULL,
    reason varchar(500),
    retired_count integer,
    completed_at timestamptz,
    CHECK ((completed_at IS NULL AND reason IS NULL AND retired_count IS NULL)
        OR (completed_at IS NOT NULL AND btrim(reason)<>'' AND retired_count>=0))
);
GRANT SELECT,INSERT,UPDATE ON registry.formal_sample_retirement_batch TO qiqihar_enterprise_runtime;
COMMENT ON TABLE registry.formal_sample_retirement_batch IS
    'Actor-owned full authoritative sample preview and durable atomic execution receipt; batch_id is the idempotency identity.';
