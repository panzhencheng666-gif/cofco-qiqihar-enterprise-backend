SET lock_timeout = '2s';
SET statement_timeout = '30s';

CREATE TABLE risk.ai_knowledge_document (
    document_id uuid PRIMARY KEY,
    title varchar(300) NOT NULL,
    source_url text NOT NULL,
    object_key text NOT NULL,
    content_sha256 char(64) NOT NULL,
    version integer NOT NULL CHECK (version > 0),
    status varchar(20) NOT NULL CHECK (status IN ('DRAFT','APPROVED','RETIRED')),
    use_scope varchar(30) NOT NULL CHECK (use_scope IN ('RETRIEVAL_ONLY','TRAINING_ALLOWED')),
    access_scope varchar(20) NOT NULL CHECK (access_scope IN ('BUSINESS','ROOT')),
    license_code varchar(40) NOT NULL,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(metadata)='object'),
    approved_by_subject varchar(160),
    approved_at timestamptz,
    created_by_subject varchar(160) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (object_key,version),
    UNIQUE (content_sha256),
    CHECK (source_url ~ '^https://'),
    CHECK (object_key ~ '^knowledge/[A-Za-z0-9._/-]{1,900}$'),
    CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
    CHECK ((status='APPROVED') = (approved_by_subject IS NOT NULL AND approved_at IS NOT NULL)),
    CHECK (use_scope<>'TRAINING_ALLOWED' OR status='APPROVED')
);

CREATE TABLE risk.ai_assistant_request (
    request_id uuid PRIMARY KEY,
    requested_by_subject varchar(160) NOT NULL,
    idempotency_key varchar(80) NOT NULL CHECK (
        idempotency_key ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{7,79}$'),
    question text NOT NULL CHECK (length(btrim(question)) BETWEEN 1 AND 2000),
    status varchar(30) NOT NULL CHECK (status IN (
        'QUEUED','RUNNING','ANSWERED','INSUFFICIENT_EVIDENCE','FAILED')),
    lease_owner varchar(160),
    lease_until timestamptz,
    attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count BETWEEN 0 AND 3),
    mode varchar(40),
    knowledge_version varchar(160),
    model_reference text,
    answer text,
    response_json jsonb,
    failure_code varchar(80),
    created_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (requested_by_subject,idempotency_key),
    CHECK ((status='RUNNING') = (lease_owner IS NOT NULL AND lease_until IS NOT NULL)),
    CHECK ((status IN ('ANSWERED','INSUFFICIENT_EVIDENCE')) = (response_json IS NOT NULL)),
    CHECK ((status='FAILED') = (failure_code IS NOT NULL))
);

CREATE TABLE risk.ai_assistant_audit (
    event_id uuid PRIMARY KEY,
    request_id uuid NOT NULL REFERENCES risk.ai_assistant_request(request_id),
    actor_type varchar(30) NOT NULL CHECK (actor_type IN ('BUSINESS','AI_NODE','SYSTEM')),
    actor_id varchar(160) NOT NULL,
    event_code varchar(50) NOT NULL,
    details_json jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(details_json)='object'),
    occurred_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX ai_assistant_request_claim_idx
    ON risk.ai_assistant_request(status,created_at,request_id)
    WHERE status IN ('QUEUED','RUNNING');
CREATE INDEX ai_assistant_request_owner_idx
    ON risk.ai_assistant_request(requested_by_subject,created_at DESC,request_id DESC);
CREATE INDEX ai_assistant_audit_recent_idx
    ON risk.ai_assistant_audit(occurred_at DESC,event_id DESC);

CREATE FUNCTION risk.reject_ai_assistant_audit_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'AI assistant audit is immutable';
END
$$;

CREATE TRIGGER ai_assistant_audit_immutable
BEFORE UPDATE OR DELETE ON risk.ai_assistant_audit
FOR EACH ROW EXECUTE FUNCTION risk.reject_ai_assistant_audit_mutation();

CREATE FUNCTION risk.protect_ai_assistant_request_identity() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.request_id<>OLD.request_id
       OR NEW.requested_by_subject<>OLD.requested_by_subject
       OR NEW.idempotency_key<>OLD.idempotency_key
       OR NEW.question<>OLD.question
       OR NEW.created_at<>OLD.created_at THEN
        RAISE EXCEPTION 'AI assistant request identity is immutable';
    END IF;
    IF OLD.status IN ('ANSWERED','INSUFFICIENT_EVIDENCE','FAILED') AND NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'Terminal AI assistant requests are immutable';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER ai_assistant_request_identity_immutable
BEFORE UPDATE ON risk.ai_assistant_request
FOR EACH ROW EXECUTE FUNCTION risk.protect_ai_assistant_request_identity();

GRANT SELECT,INSERT,UPDATE ON risk.ai_assistant_request TO qiqihar_risk_runtime;
GRANT SELECT,INSERT ON risk.ai_assistant_audit TO qiqihar_risk_runtime;
GRANT SELECT,INSERT,UPDATE ON risk.ai_knowledge_document TO qiqihar_risk_runtime;
GRANT EXECUTE ON FUNCTION
    risk.reject_ai_assistant_audit_mutation(),
    risk.protect_ai_assistant_request_identity()
TO qiqihar_risk_runtime;

COMMENT ON TABLE risk.ai_knowledge_document IS
    'Private OSS-backed knowledge metadata with explicit retrieval/training scope and approval provenance.';
COMMENT ON TABLE risk.ai_assistant_request IS
    'Short-leased asynchronous questions answered by the private Qiliang AI node; no long database connection is held.';
COMMENT ON TABLE risk.ai_assistant_audit IS
    'Immutable creation, claim, completion and failure trail for Qiliang AI assistant requests.';
