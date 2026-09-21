ALTER TABLE risk.ai_training_policy
    DROP CONSTRAINT ai_training_policy_auto_activation_enabled_check;
ALTER TABLE risk.ai_training_policy
    ADD COLUMN minimum_shadow_labels integer NOT NULL DEFAULT 10
        CHECK (minimum_shadow_labels >= 3),
    ADD COLUMN minimum_shadow_hours integer NOT NULL DEFAULT 24
        CHECK (minimum_shadow_hours >= 0),
    ADD COLUMN minimum_shadow_f1 numeric(7,6) NOT NULL DEFAULT 0.600000
        CHECK (minimum_shadow_f1 BETWEEN 0 AND 1),
    ADD COLUMN maximum_f1_regression numeric(7,6) NOT NULL DEFAULT 0.020000
        CHECK (maximum_f1_regression BETWEEN 0 AND 1),
    ADD COLUMN rollback_f1_drop numeric(7,6) NOT NULL DEFAULT 0.050000
        CHECK (rollback_f1_drop BETWEEN 0 AND 1),
    ADD CONSTRAINT ai_training_policy_auto_activation_requires_candidate
        CHECK (NOT auto_activation_enabled OR automatic_candidate_enabled);

UPDATE risk.ai_training_policy
SET auto_activation_enabled=true,updated_at=now()
WHERE model_id IN (
    SELECT model_id FROM risk.ai_model
    WHERE model_code IN ('risk-domain-classifier-v1','risk-reasoning-llm-v1'));

ALTER TABLE risk.model_version DROP CONSTRAINT model_version_status_code_check;
ALTER TABLE risk.model_version ADD CONSTRAINT model_version_status_code_check
    CHECK (status_code IN (
        'CANDIDATE','SHADOW','APPROVED','ACTIVE','STANDBY','REJECTED','RETIRED'));
ALTER TABLE risk.model_version DROP CONSTRAINT model_version_check3;
ALTER TABLE risk.model_version ADD CONSTRAINT model_version_check3
    CHECK (status_code NOT IN ('APPROVED','ACTIVE','STANDBY','RETIRED') OR approved_at IS NOT NULL);
ALTER TABLE risk.model_version DROP CONSTRAINT model_version_check5;
ALTER TABLE risk.model_version ADD CONSTRAINT model_version_check5
    CHECK ((status_code IN ('ACTIVE','STANDBY','RETIRED')) = (activated_at IS NOT NULL));

CREATE TABLE risk.model_live_prediction (
    model_id uuid NOT NULL,
    model_version integer NOT NULL,
    assessment_id uuid NOT NULL REFERENCES risk.risk_assessment(assessment_id),
    lifecycle_phase varchar(20) NOT NULL CHECK (lifecycle_phase IN ('SHADOW','ACTIVE','STANDBY')),
    predicted_positive boolean NOT NULL,
    positive_probability numeric(9,8) NOT NULL CHECK (positive_probability BETWEEN 0 AND 1),
    scored_at timestamptz NOT NULL,
    PRIMARY KEY (model_id,model_version,assessment_id),
    FOREIGN KEY (model_id,model_version) REFERENCES risk.model_version(model_id,version)
);

CREATE INDEX model_live_prediction_assessment_idx
    ON risk.model_live_prediction(assessment_id,scored_at);

CREATE TABLE risk.model_activation_event (
    event_id uuid PRIMARY KEY,
    model_id uuid NOT NULL REFERENCES risk.ai_model(model_id),
    from_version integer,
    to_version integer NOT NULL,
    event_code varchar(30) NOT NULL
        CHECK (event_code IN ('AUTO_ACTIVATED','AUTO_REJECTED','AUTO_ROLLED_BACK')),
    metric_definition jsonb NOT NULL,
    reason_code varchar(80) NOT NULL,
    occurred_at timestamptz NOT NULL,
    FOREIGN KEY (model_id,from_version) REFERENCES risk.model_version(model_id,version),
    FOREIGN KEY (model_id,to_version) REFERENCES risk.model_version(model_id,version),
    CHECK (jsonb_typeof(metric_definition)='object')
);

CREATE INDEX model_activation_event_model_idx
    ON risk.model_activation_event(model_id,occurred_at DESC);

CREATE OR REPLACE FUNCTION risk.enforce_model_version_transition()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    training_status varchar(30);
    model_status varchar(20);
BEGIN
    SELECT status_code INTO STRICT model_status
    FROM risk.ai_model WHERE model_id=NEW.model_id;
    IF model_status<>'ACTIVE' AND (
        TG_OP='INSERT' OR NEW.status_code IN ('SHADOW','APPROVED','ACTIVE')
    ) THEN
        RAISE EXCEPTION 'Model version creation and promotion require an ACTIVE AI model identity';
    END IF;
    IF TG_OP='INSERT' THEN
        IF NEW.status_code<>'CANDIDATE' THEN
            RAISE EXCEPTION 'Model versions must be created as CANDIDATE';
        END IF;
        IF NEW.shadow_started_at IS NOT NULL OR NEW.shadow_completed_at IS NOT NULL
           OR NEW.approved_at IS NOT NULL OR NEW.activated_at IS NOT NULL
           OR NEW.retired_at IS NOT NULL THEN
            RAISE EXCEPTION 'Model candidates cannot prefill lifecycle evidence';
        END IF;
        SELECT status_code INTO STRICT training_status
        FROM risk.training_run
        WHERE training_run_id=NEW.training_run_id
          AND model_id=NEW.model_id AND domain_code=NEW.domain_code;
        IF training_status<>'SUCCEEDED' THEN
            RAISE EXCEPTION 'Model candidates require a SUCCEEDED training run';
        END IF;
        RETURN NEW;
    END IF;
    IF ROW(
        OLD.model_id,OLD.version,OLD.domain_code,OLD.training_run_id,
        OLD.artifact_reference,OLD.artifact_sha256,OLD.metric_definition,
        OLD.threshold_definition,OLD.created_at
    ) IS DISTINCT FROM ROW(
        NEW.model_id,NEW.version,NEW.domain_code,NEW.training_run_id,
        NEW.artifact_reference,NEW.artifact_sha256,NEW.metric_definition,
        NEW.threshold_definition,NEW.created_at
    ) THEN
        RAISE EXCEPTION 'Model identity, lineage and artifacts are immutable';
    END IF;
    IF (OLD.shadow_started_at IS NOT NULL AND OLD.shadow_started_at IS DISTINCT FROM NEW.shadow_started_at)
       OR (OLD.shadow_completed_at IS NOT NULL AND OLD.shadow_completed_at IS DISTINCT FROM NEW.shadow_completed_at)
       OR (OLD.approved_by_subject IS NOT NULL AND OLD.approved_by_subject IS DISTINCT FROM NEW.approved_by_subject)
       OR (OLD.approved_at IS NOT NULL AND OLD.approved_at IS DISTINCT FROM NEW.approved_at)
       OR (OLD.activated_at IS NOT NULL AND OLD.activated_at IS DISTINCT FROM NEW.activated_at)
       OR (OLD.retired_at IS NOT NULL AND OLD.retired_at IS DISTINCT FROM NEW.retired_at) THEN
        RAISE EXCEPTION 'Recorded model lifecycle evidence is immutable';
    END IF;
    IF OLD.status_code=NEW.status_code THEN
        IF ROW(OLD.shadow_started_at,OLD.shadow_completed_at,OLD.approved_by_subject,
               OLD.approved_at,OLD.activated_at,OLD.retired_at)
           IS DISTINCT FROM ROW(NEW.shadow_started_at,NEW.shadow_completed_at,NEW.approved_by_subject,
               NEW.approved_at,NEW.activated_at,NEW.retired_at) THEN
            RAISE EXCEPTION 'Model lifecycle evidence changes require a state transition';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.status_code='CANDIDATE' AND NEW.status_code NOT IN ('SHADOW','REJECTED') THEN
        RAISE EXCEPTION 'CANDIDATE models must enter SHADOW before activation';
    END IF;
    IF NEW.status_code='SHADOW' AND NEW.shadow_started_at IS NULL THEN
        RAISE EXCEPTION 'SHADOW models require shadow_started_at';
    END IF;
    IF OLD.status_code='SHADOW' AND NEW.status_code NOT IN ('APPROVED','REJECTED') THEN
        RAISE EXCEPTION 'SHADOW models must pass automatic gates or be rejected';
    END IF;
    IF NEW.status_code='APPROVED'
       AND (NEW.shadow_started_at IS NULL OR NEW.shadow_completed_at IS NULL) THEN
        RAISE EXCEPTION 'Automatic promotion requires a completed shadow evaluation';
    END IF;
    IF NEW.status_code='APPROVED' AND NOT EXISTS (
        SELECT 1 FROM risk.model_evaluation evaluation
        WHERE evaluation.model_id=NEW.model_id
          AND evaluation.model_version=NEW.version AND evaluation.passed
          AND evaluation.evaluation_window_start>=NEW.shadow_started_at
          AND evaluation.evaluation_window_end<=NEW.shadow_completed_at
    ) THEN
        RAISE EXCEPTION 'Automatic promotion requires a passed recorded evaluation';
    END IF;
    IF NEW.status_code='ACTIVE' AND OLD.status_code NOT IN ('APPROVED','STANDBY') THEN
        RAISE EXCEPTION 'Only APPROVED or STANDBY models can become ACTIVE';
    END IF;
    IF NEW.status_code='ACTIVE' AND NEW.activated_at IS NULL THEN
        RAISE EXCEPTION 'ACTIVE models require activated_at';
    END IF;
    IF OLD.status_code='APPROVED' AND NEW.status_code NOT IN ('ACTIVE','REJECTED') THEN
        RAISE EXCEPTION 'APPROVED models must be activated or rejected';
    END IF;
    IF OLD.status_code='ACTIVE' AND NEW.status_code NOT IN ('STANDBY','RETIRED') THEN
        RAISE EXCEPTION 'ACTIVE models can only become STANDBY or RETIRED';
    END IF;
    IF OLD.status_code='STANDBY' AND NEW.status_code NOT IN ('ACTIVE','RETIRED') THEN
        RAISE EXCEPTION 'STANDBY models can only be restored or retired';
    END IF;
    IF NEW.status_code='RETIRED' AND NEW.retired_at IS NULL THEN
        RAISE EXCEPTION 'RETIRED models require retired_at';
    END IF;
    IF OLD.status_code IN ('REJECTED','RETIRED') THEN
        RAISE EXCEPTION 'Terminal model versions cannot transition';
    END IF;
    RETURN NEW;
END
$$;

GRANT SELECT,INSERT ON TABLE risk.model_live_prediction,risk.model_activation_event
    TO qiqihar_enterprise_runtime;
GRANT UPDATE ON TABLE risk.model_version TO qiqihar_enterprise_runtime;

COMMENT ON TABLE risk.model_live_prediction IS
    'Point-in-time predictions created before real outcomes arrive; used for automatic shadow promotion and rollback.';
COMMENT ON TABLE risk.model_activation_event IS
    'Immutable automatic activation, rejection and rollback ledger with measured quality evidence.';
