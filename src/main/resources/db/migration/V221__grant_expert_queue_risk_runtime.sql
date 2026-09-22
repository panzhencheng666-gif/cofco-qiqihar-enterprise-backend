SET lock_timeout = '2s';
SET statement_timeout = '30s';

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='qiqihar_risk_runtime') THEN
        CREATE ROLE qiqihar_risk_runtime NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
    END IF;
END
$$;

GRANT USAGE ON SCHEMA risk TO qiqihar_risk_runtime;
GRANT SELECT,INSERT ON risk.expert_dataset_snapshot TO qiqihar_risk_runtime;
GRANT SELECT,INSERT,UPDATE ON risk.expert_training_task TO qiqihar_risk_runtime;
GRANT SELECT,INSERT ON risk.expert_training_audit TO qiqihar_risk_runtime;
