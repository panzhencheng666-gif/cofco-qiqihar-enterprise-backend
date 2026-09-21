\set ON_ERROR_STOP on

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='qiqihar_risk_runtime') THEN
        CREATE ROLE qiqihar_risk_runtime NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='qiqihar_risk_runtime_login') THEN
        CREATE ROLE qiqihar_risk_runtime_login LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
    END IF;
END
$$;

ALTER ROLE qiqihar_risk_runtime_login LOGIN PASSWORD :'risk_runtime_password';
ALTER ROLE qiqihar_risk_runtime_login CONNECTION LIMIT 6;
GRANT qiqihar_risk_runtime TO qiqihar_risk_runtime_login;

GRANT CONNECT ON DATABASE :"database_name" TO qiqihar_risk_runtime_login;
REVOKE CREATE ON SCHEMA public FROM qiqihar_risk_runtime,qiqihar_risk_runtime_login;
REVOKE ALL ON SCHEMA
    platform,production,market,logistics,supply,reporting,workflow,overview,evidence,registry
FROM qiqihar_risk_runtime,qiqihar_risk_runtime_login;
REVOKE ALL ON ALL TABLES IN SCHEMA
    platform,production,market,logistics,supply,reporting,workflow,overview,evidence,registry
FROM qiqihar_risk_runtime,qiqihar_risk_runtime_login;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA
    platform,production,market,logistics,supply,reporting,workflow,overview,evidence,registry
FROM qiqihar_risk_runtime,qiqihar_risk_runtime_login;

GRANT USAGE ON SCHEMA risk TO qiqihar_risk_runtime;
REVOKE ALL ON ALL TABLES IN SCHEMA risk FROM qiqihar_risk_runtime;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA risk FROM qiqihar_risk_runtime;
GRANT SELECT ON ALL TABLES IN SCHEMA risk TO qiqihar_risk_runtime;
GRANT INSERT ON TABLE
    risk.source_fact_snapshot,
    risk.training_schedule_execution,
    risk.training_snapshot,
    risk.training_example,
    risk.training_run,
    risk.model_version,
    risk.risk_case_feedback,
    risk.model_live_prediction,
    risk.model_evaluation,
    risk.model_activation_event
TO qiqihar_risk_runtime;
GRANT UPDATE ON TABLE
    risk.ai_model,
    risk.ai_training_policy,
    risk.training_schedule_execution,
    risk.training_run,
    risk.model_version
TO qiqihar_risk_runtime;
GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA risk TO qiqihar_risk_runtime;

ALTER DEFAULT PRIVILEGES FOR ROLE :"migration_owner" IN SCHEMA risk
    GRANT SELECT ON TABLES TO qiqihar_risk_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE :"migration_owner" IN SCHEMA risk
    GRANT USAGE,SELECT ON SEQUENCES TO qiqihar_risk_runtime;

ALTER ROLE qiqihar_risk_runtime_login SET statement_timeout='15s';
ALTER ROLE qiqihar_risk_runtime_login SET lock_timeout='2s';
ALTER ROLE qiqihar_risk_runtime_login SET idle_in_transaction_session_timeout='30s';
ALTER ROLE qiqihar_risk_runtime_login IN DATABASE :"database_name"
    SET search_path=risk,pg_catalog;
