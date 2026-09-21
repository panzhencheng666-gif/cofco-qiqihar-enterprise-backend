ALTER TABLE risk.ai_model DROP CONSTRAINT ai_model_model_kind_check;
ALTER TABLE risk.ai_model ADD CONSTRAINT ai_model_model_kind_check
    CHECK (model_kind IN ('DOMAIN_LLM','RISK_CLASSIFIER','ANOMALY','FORECAST','RERANKER'));

CREATE TABLE risk.training_example (
    training_snapshot_id uuid NOT NULL,
    ordinal integer NOT NULL CHECK (ordinal > 0),
    assessment_id uuid NOT NULL REFERENCES risk.risk_assessment(assessment_id),
    resolved_at timestamptz NOT NULL,
    label_code varchar(30) NOT NULL
        CHECK (label_code IN ('CONFIRMED','FALSE_POSITIVE','MISSED_RISK')),
    positive_label boolean NOT NULL,
    feature_payload jsonb NOT NULL,
    payload_sha256 char(64) NOT NULL CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (training_snapshot_id,ordinal),
    UNIQUE (training_snapshot_id,assessment_id),
    FOREIGN KEY (training_snapshot_id)
        REFERENCES risk.training_snapshot(training_snapshot_id),
    CHECK (jsonb_typeof(feature_payload)='object'),
    CHECK ((label_code IN ('CONFIRMED','MISSED_RISK')) = positive_label)
);

ALTER TABLE risk.ai_training_policy
    ADD CONSTRAINT ai_training_policy_identity_unique UNIQUE (training_policy_id,model_id);

CREATE TABLE risk.training_schedule_execution (
    execution_id uuid PRIMARY KEY,
    training_policy_id uuid NOT NULL
        REFERENCES risk.ai_training_policy(training_policy_id),
    model_id uuid NOT NULL REFERENCES risk.ai_model(model_id),
    scheduled_local_date date NOT NULL,
    trigger_code varchar(20) NOT NULL CHECK (trigger_code IN ('DAILY','MANUAL')),
    requested_by_subject varchar(160) NOT NULL,
    due_at timestamptz NOT NULL,
    status_code varchar(20) NOT NULL DEFAULT 'QUEUED'
        CHECK (status_code IN ('QUEUED','RUNNING','SUCCEEDED','SKIPPED','FAILED')),
    lease_owner varchar(160),
    lease_until timestamptz,
    training_snapshot_id uuid REFERENCES risk.training_snapshot(training_snapshot_id),
    training_run_id uuid REFERENCES risk.training_run(training_run_id),
    outcome_code varchar(80),
    outcome_message text,
    created_at timestamptz NOT NULL DEFAULT now(),
    started_at timestamptz,
    completed_at timestamptz,
    FOREIGN KEY (training_policy_id,model_id)
        REFERENCES risk.ai_training_policy(training_policy_id,model_id),
    CHECK ((lease_owner IS NULL) = (lease_until IS NULL)),
    CHECK ((status_code='QUEUED' AND started_at IS NULL AND completed_at IS NULL)
        OR (status_code='RUNNING' AND started_at IS NOT NULL AND completed_at IS NULL)
        OR (status_code IN ('SUCCEEDED','SKIPPED','FAILED') AND completed_at IS NOT NULL)),
    CHECK (completed_at IS NULL OR started_at IS NULL OR completed_at >= started_at),
    CHECK (status_code IN ('QUEUED','RUNNING') OR outcome_code IS NOT NULL)
);

CREATE UNIQUE INDEX training_schedule_one_daily_execution
    ON risk.training_schedule_execution(training_policy_id,scheduled_local_date)
    WHERE trigger_code='DAILY';
CREATE INDEX training_schedule_claim_idx
    ON risk.training_schedule_execution(status_code,due_at,lease_until);

INSERT INTO risk.ai_model(
    model_id,model_code,model_name,model_kind,domain_code,base_model_reference,
    purpose_definition,status_code,created_by_subject,updated_by_subject
) VALUES
('21500000-0000-0000-0000-000000000001','risk-domain-classifier-v1',
 '风险案例领域分类模型','RISK_CLASSIFIER','CROSS_DOMAIN',
 'builtin://bernoulli-naive-bayes/v1',
 '{"decision":"risk_probability","advisoryOnly":true,"labels":["CONFIRMED","MISSED_RISK","FALSE_POSITIVE"]}',
 'DRAFT','system:migration-v215','system:migration-v215'),
('21500000-0000-0000-0000-000000000002','risk-reasoning-llm-v1',
 '风险研判独立大模型','DOMAIN_LLM','CROSS_DOMAIN',
 'openai-compatible://configure-risk-domain-base-model',
 '{"decision":"evidence_bound_independent_judgement","advisoryOnly":true,"requiredOutputs":["supportingEvidence","contradictingEvidence","uncertainty","recommendedActions"]}',
 'DRAFT','system:migration-v215','system:migration-v215');

UPDATE risk.ai_model
SET status_code='ACTIVE',updated_by_subject='system:approved-risk-governance',updated_at=now()
WHERE model_code='risk-domain-classifier-v1';

INSERT INTO risk.ai_training_policy(
    training_policy_id,model_id,scheduled_local_time,schedule_timezone,
    training_window_days,minimum_new_labels,automatic_candidate_enabled,
    auto_activation_enabled,enabled,approved_by_subject,approved_at
) VALUES (
    '21500000-0000-0000-0000-000000000011',
    '21500000-0000-0000-0000-000000000001',
    '02:15:00','Asia/Shanghai',90,10,true,false,true,
    'system:approved-risk-governance',now()
),(
    '21500000-0000-0000-0000-000000000012',
    '21500000-0000-0000-0000-000000000002',
    '03:15:00','Asia/Shanghai',180,50,true,false,false,
    'system:approved-risk-governance',now()
);

REVOKE ALL ON TABLE risk.training_example,risk.training_schedule_execution FROM PUBLIC;
GRANT SELECT,INSERT ON TABLE risk.training_example TO qiqihar_enterprise_runtime;
GRANT SELECT,INSERT,UPDATE ON TABLE risk.training_schedule_execution TO qiqihar_enterprise_runtime;

COMMENT ON TABLE risk.training_example IS
    'Immutable point-in-time examples copied from resolved real risk cases; personal reviewer identity is excluded from feature payloads.';
COMMENT ON TABLE risk.training_schedule_execution IS
    'Database-backed daily/manual training queue with one daily execution per policy and no automatic activation path.';

-- Reassert the non-negotiable promotion boundary in this operational migration.
ALTER TABLE risk.ai_training_policy DROP CONSTRAINT ai_training_policy_auto_activation_enabled_check;
ALTER TABLE risk.ai_training_policy ADD CONSTRAINT ai_training_policy_auto_activation_enabled_check
    CHECK (NOT auto_activation_enabled);
