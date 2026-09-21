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
GRANT SELECT,INSERT,UPDATE ON ALL TABLES IN SCHEMA risk TO qiqihar_risk_runtime;
GRANT USAGE,SELECT,UPDATE ON ALL SEQUENCES IN SCHEMA risk TO qiqihar_risk_runtime;

ALTER DEFAULT PRIVILEGES FOR ROLE qiqihar_migration_owner IN SCHEMA risk
    GRANT SELECT,INSERT,UPDATE ON TABLES TO qiqihar_risk_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE qiqihar_migration_owner IN SCHEMA risk
    GRANT USAGE,SELECT,UPDATE ON SEQUENCES TO qiqihar_risk_runtime;

ALTER ROLE qiqihar_risk_runtime_login SET statement_timeout='15s';
ALTER ROLE qiqihar_risk_runtime_login SET lock_timeout='2s';
ALTER ROLE qiqihar_risk_runtime_login SET idle_in_transaction_session_timeout='30s';
ALTER ROLE qiqihar_risk_runtime_login IN DATABASE :"database_name"
    SET search_path=risk,pg_catalog;
