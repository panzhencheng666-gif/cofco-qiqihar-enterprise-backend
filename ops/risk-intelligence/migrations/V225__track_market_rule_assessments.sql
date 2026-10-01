SET lock_timeout = '2s';
SET statement_timeout = '30s';

CREATE TABLE risk.market_rule_assessment_evaluation (
    rule_set_id uuid NOT NULL,
    rule_set_version integer NOT NULL,
    source_fact_snapshot_id uuid NOT NULL
        REFERENCES risk.source_fact_snapshot(snapshot_id),
    matched boolean NOT NULL,
    assessment_id uuid REFERENCES risk.risk_assessment(assessment_id),
    evaluated_at timestamptz NOT NULL,
    PRIMARY KEY (rule_set_id, rule_set_version, source_fact_snapshot_id),
    FOREIGN KEY (rule_set_id, rule_set_version)
        REFERENCES risk.risk_rule_set_version(rule_set_id, version),
    CHECK (matched = (assessment_id IS NOT NULL))
);

GRANT SELECT, INSERT ON TABLE risk.market_rule_assessment_evaluation
    TO qiqihar_enterprise_runtime;

COMMENT ON TABLE risk.market_rule_assessment_evaluation IS
    'Immutable per-fact, per-rule evaluation ledger; this migration activates no rule or alert.';
