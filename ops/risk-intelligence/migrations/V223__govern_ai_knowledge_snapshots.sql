SET lock_timeout = '2s';
SET statement_timeout = '30s';

ALTER TABLE risk.ai_knowledge_document
    ADD COLUMN source_kind varchar(24) NOT NULL DEFAULT 'OFFICIAL',
    ADD COLUMN body_text text,
    ADD COLUMN search_excerpt text,
    ADD COLUMN verification_note text,
    ADD COLUMN rights_evidence text,
    ADD COLUMN retired_by_subject varchar(160),
    ADD COLUMN retired_at timestamptz;

ALTER TABLE risk.ai_knowledge_document
    DROP CONSTRAINT ai_knowledge_document_content_sha256_key;
ALTER TABLE risk.ai_knowledge_document
    DROP CONSTRAINT ai_knowledge_document_object_key_check;
ALTER TABLE risk.ai_knowledge_document
    ADD CONSTRAINT ai_knowledge_object_key_check
        CHECK (length(object_key) BETWEEN 11 AND 910 AND
               object_key ~ '^knowledge/[A-Za-z0-9._/-]+$');
CREATE INDEX ai_knowledge_content_hash_idx
    ON risk.ai_knowledge_document(content_sha256);

ALTER TABLE risk.ai_assistant_request
    ADD COLUMN root_access boolean NOT NULL DEFAULT false;

CREATE FUNCTION risk.protect_ai_assistant_root_access() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.root_access <> OLD.root_access THEN
        RAISE EXCEPTION 'AI assistant request access scope is immutable';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER ai_assistant_root_access_immutable
BEFORE UPDATE ON risk.ai_assistant_request
FOR EACH ROW EXECUTE FUNCTION risk.protect_ai_assistant_root_access();

ALTER TABLE risk.ai_knowledge_document
    ADD CONSTRAINT ai_knowledge_source_kind_check
        CHECK (source_kind IN ('SYSTEM_RECORD','OFFICIAL','MEDIA','OTHER','SEARCH_CANDIDATE')),
    ADD CONSTRAINT ai_knowledge_snapshot_size_check
        CHECK (body_text IS NULL OR length(body_text) BETWEEN 1 AND 1000000),
    ADD CONSTRAINT ai_knowledge_excerpt_size_check
        CHECK (search_excerpt IS NULL OR length(search_excerpt) BETWEEN 1 AND 4000),
    ADD CONSTRAINT ai_knowledge_approval_evidence_check
        CHECK (status <> 'APPROVED' OR
               (body_text IS NOT NULL AND verification_note IS NOT NULL AND
                length(btrim(verification_note)) > 0)) NOT VALID,
    ADD CONSTRAINT ai_knowledge_training_rights_check
        CHECK (use_scope <> 'TRAINING_ALLOWED' OR
               (rights_evidence IS NOT NULL AND length(btrim(rights_evidence)) > 0)) NOT VALID,
    ADD CONSTRAINT ai_knowledge_retirement_check
        CHECK ((status = 'RETIRED') =
               (retired_by_subject IS NOT NULL AND retired_at IS NOT NULL)) NOT VALID;

CREATE FUNCTION risk.protect_ai_knowledge_snapshot() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.document_id <> OLD.document_id OR NEW.title <> OLD.title OR
       NEW.source_url <> OLD.source_url OR NEW.object_key <> OLD.object_key OR
       NEW.content_sha256 <> OLD.content_sha256 OR NEW.version <> OLD.version OR
       NEW.source_kind <> OLD.source_kind OR NEW.body_text IS DISTINCT FROM OLD.body_text OR
       NEW.search_excerpt IS DISTINCT FROM OLD.search_excerpt OR
       NEW.access_scope <> OLD.access_scope OR NEW.license_code <> OLD.license_code OR
       NEW.metadata IS DISTINCT FROM OLD.metadata OR
       NEW.created_by_subject <> OLD.created_by_subject OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'AI knowledge source snapshot is immutable; create a new version';
    END IF;
    IF OLD.status = 'RETIRED' OR
       (OLD.status = 'APPROVED' AND NEW.status = 'APPROVED' AND NEW IS DISTINCT FROM OLD) OR
       (OLD.status = 'APPROVED' AND NEW.status <> 'RETIRED') OR
       (OLD.status = 'DRAFT' AND NEW.status NOT IN ('DRAFT','APPROVED','RETIRED')) THEN
        RAISE EXCEPTION 'Invalid AI knowledge lifecycle transition';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER ai_knowledge_snapshot_immutable
BEFORE UPDATE ON risk.ai_knowledge_document
FOR EACH ROW EXECUTE FUNCTION risk.protect_ai_knowledge_snapshot();

CREATE INDEX ai_knowledge_published_idx
    ON risk.ai_knowledge_document(access_scope,source_kind,approved_at DESC)
    WHERE status = 'APPROVED';

CREATE UNIQUE INDEX ai_knowledge_one_published_version_idx
    ON risk.ai_knowledge_document(object_key) WHERE status = 'APPROVED';

CREATE TABLE risk.ai_knowledge_audit (
    event_id uuid PRIMARY KEY,
    document_id uuid NOT NULL REFERENCES risk.ai_knowledge_document(document_id),
    actor_subject varchar(160) NOT NULL,
    event_code varchar(30) NOT NULL CHECK (event_code IN ('REGISTERED','APPROVED','RETIRED')),
    occurred_at timestamptz NOT NULL DEFAULT now()
);

CREATE TRIGGER ai_knowledge_audit_immutable
BEFORE UPDATE OR DELETE ON risk.ai_knowledge_audit
FOR EACH ROW EXECUTE FUNCTION risk.reject_ai_assistant_audit_mutation();

GRANT SELECT,INSERT ON risk.ai_knowledge_audit TO qiqihar_risk_runtime;

GRANT EXECUTE ON FUNCTION risk.protect_ai_knowledge_snapshot()
    TO qiqihar_risk_runtime;
GRANT EXECUTE ON FUNCTION risk.protect_ai_assistant_root_access()
    TO qiqihar_risk_runtime;
