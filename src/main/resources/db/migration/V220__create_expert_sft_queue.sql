SET lock_timeout = '2s';
SET statement_timeout = '30s';

CREATE TABLE risk.expert_dataset_snapshot (
    snapshot_id uuid PRIMARY KEY,
    dataset_id text NOT NULL CHECK (dataset_id ~ '^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$'),
    version integer NOT NULL CHECK (version > 0),
    dataset_sha256 text NOT NULL CHECK (dataset_sha256 ~ '^[0-9a-f]{64}$'),
    dataset_json jsonb NOT NULL CHECK (jsonb_typeof(dataset_json)='object'),
    train_count integer NOT NULL CHECK (train_count > 0),
    valid_count integer NOT NULL CHECK (valid_count > 0),
    test_count integer NOT NULL CHECK (test_count > 0),
    created_by_subject text NOT NULL CHECK (length(created_by_subject) BETWEEN 1 AND 200),
    created_at timestamptz NOT NULL,
    UNIQUE (dataset_id,version),
    UNIQUE (dataset_id,dataset_sha256)
);

CREATE TABLE risk.expert_training_task (
    task_id uuid PRIMARY KEY,
    dataset_snapshot_id uuid NOT NULL REFERENCES risk.expert_dataset_snapshot(snapshot_id),
    training_kind text NOT NULL DEFAULT 'EXPERT_SFT' CHECK (training_kind='EXPERT_SFT'),
    model_reference text NOT NULL CHECK (length(model_reference) BETWEEN 1 AND 500),
    config_json jsonb NOT NULL CHECK (jsonb_typeof(config_json)='object' AND octet_length(config_json::text)<=4096),
    request_sha256 text NOT NULL CHECK (request_sha256 ~ '^[0-9a-f]{64}$'),
    requested_by_subject text NOT NULL CHECK (length(requested_by_subject) BETWEEN 1 AND 200),
    idempotency_key text NOT NULL CHECK (length(idempotency_key) BETWEEN 1 AND 120),
    status text NOT NULL CHECK (status IN ('QUEUED','RUNNING','CANCEL_REQUESTED','CANCELLED','SUCCEEDED','FAILED')),
    lease_owner text,
    lease_until timestamptz,
    attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count BETWEEN 0 AND 3),
    progress_percent integer NOT NULL DEFAULT 0 CHECK (progress_percent BETWEEN 0 AND 100),
    progress_phase text,
    progress_at timestamptz,
    cancellation_requested_by_subject text,
    cancellation_requested_at timestamptz,
    cancelled_at timestamptz,
    uploaded_artifact_reference text,
    uploaded_artifact_sha256 text CHECK (uploaded_artifact_sha256 IS NULL OR uploaded_artifact_sha256 ~ '^[0-9a-f]{64}$'),
    artifact_reference text,
    artifact_sha256 text CHECK (artifact_sha256 IS NULL OR artifact_sha256 ~ '^[0-9a-f]{64}$'),
    metrics_json jsonb CHECK (metrics_json IS NULL OR
        (jsonb_typeof(metrics_json)='object' AND octet_length(metrics_json::text)<=4096)),
    failure_code text CHECK (failure_code IS NULL OR failure_code ~ '^[A-Z][A-Z0-9_]{0,79}$'),
    failure_message text CHECK (failure_message IS NULL OR length(failure_message)<=500),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    completed_at timestamptz,
    UNIQUE (requested_by_subject,idempotency_key),
    CHECK ((lease_owner IS NULL)=(lease_until IS NULL)),
    CHECK (progress_phase IS NULL OR progress_phase IN ('PREPARING','LOCAL_TRAINING','PACKAGING','UPLOADING','COMPLETING')),
    CHECK ((cancellation_requested_by_subject IS NULL)=(cancellation_requested_at IS NULL)),
    CHECK (status NOT IN ('CANCEL_REQUESTED','CANCELLED') OR cancellation_requested_at IS NOT NULL),
    CHECK ((status IN ('RUNNING','CANCEL_REQUESTED'))=(lease_owner IS NOT NULL)),
    CHECK ((status='CANCELLED')=(cancelled_at IS NOT NULL)),
    CHECK ((status='SUCCEEDED')=(artifact_reference IS NOT NULL AND artifact_sha256 IS NOT NULL AND metrics_json IS NOT NULL)),
    CHECK ((status='FAILED')=(failure_code IS NOT NULL)),
    CHECK ((status IN ('CANCELLED','SUCCEEDED','FAILED'))=(completed_at IS NOT NULL)),
    CHECK (artifact_reference IS NULL OR artifact_reference=uploaded_artifact_reference),
    CHECK (artifact_sha256 IS NULL OR artifact_sha256=uploaded_artifact_sha256)
);

CREATE TABLE risk.expert_training_audit (
    event_id uuid PRIMARY KEY,
    task_id uuid NOT NULL REFERENCES risk.expert_training_task(task_id),
    actor_type text NOT NULL CHECK (actor_type IN ('BUSINESS','TRAINING_NODE','SYSTEM')),
    actor_id text NOT NULL CHECK (length(actor_id) BETWEEN 1 AND 200),
    event_code text NOT NULL CHECK (length(event_code) BETWEEN 1 AND 80),
    details_json jsonb NOT NULL DEFAULT '{}'::jsonb
        CHECK (jsonb_typeof(details_json)='object' AND octet_length(details_json::text)<=4096),
    occurred_at timestamptz NOT NULL
);

CREATE INDEX expert_training_task_claim_idx
    ON risk.expert_training_task(status,created_at,task_id)
    WHERE status IN ('QUEUED','RUNNING','CANCEL_REQUESTED');
CREATE INDEX expert_training_task_recent_idx
    ON risk.expert_training_task(created_at DESC,task_id DESC);
CREATE INDEX expert_training_audit_recent_idx
    ON risk.expert_training_audit(occurred_at DESC,event_id DESC);

CREATE FUNCTION risk.reject_expert_immutable_change() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'expert snapshot and audit rows are immutable';
    RETURN OLD;
END
$$;

CREATE TRIGGER expert_dataset_snapshot_immutable
BEFORE UPDATE OR DELETE ON risk.expert_dataset_snapshot
FOR EACH ROW EXECUTE FUNCTION risk.reject_expert_immutable_change();

CREATE TRIGGER expert_training_audit_immutable
BEFORE UPDATE OR DELETE ON risk.expert_training_audit
FOR EACH ROW EXECUTE FUNCTION risk.reject_expert_immutable_change();

CREATE FUNCTION risk.protect_expert_task_identity() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.dataset_snapshot_id IS DISTINCT FROM NEW.dataset_snapshot_id
       OR OLD.training_kind IS DISTINCT FROM NEW.training_kind
       OR OLD.model_reference IS DISTINCT FROM NEW.model_reference
       OR OLD.config_json IS DISTINCT FROM NEW.config_json
       OR OLD.request_sha256 IS DISTINCT FROM NEW.request_sha256
       OR OLD.requested_by_subject IS DISTINCT FROM NEW.requested_by_subject
       OR OLD.idempotency_key IS DISTINCT FROM NEW.idempotency_key
       OR (OLD.artifact_reference IS NOT NULL AND
           (OLD.artifact_reference IS DISTINCT FROM NEW.artifact_reference OR
            OLD.artifact_sha256 IS DISTINCT FROM NEW.artifact_sha256)) THEN
        RAISE EXCEPTION 'expert task identity and artifact fields are immutable';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER expert_training_task_identity_immutable
BEFORE UPDATE ON risk.expert_training_task
FOR EACH ROW EXECUTE FUNCTION risk.protect_expert_task_identity();

GRANT SELECT,INSERT ON risk.expert_dataset_snapshot TO qiqihar_enterprise_runtime;
GRANT SELECT,INSERT,UPDATE ON risk.expert_training_task TO qiqihar_enterprise_runtime;
GRANT SELECT,INSERT ON risk.expert_training_audit TO qiqihar_enterprise_runtime;

COMMENT ON TABLE risk.expert_dataset_snapshot IS
    'Immutable caller-declared EXPERT_SFT datasets; provenance and quality are not independently asserted.';
COMMENT ON TABLE risk.expert_training_task IS
    'Persistent EXPERT_SFT queue, separate from DOMAIN_LLM classification training.';
