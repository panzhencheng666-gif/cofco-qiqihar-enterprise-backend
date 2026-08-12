-- DEF-103: V114's authorization-aware checkpoint function still accepted a
-- subject selected by the shared application runtime. Keep its implementation
-- byte-for-byte in V114, but move execution behind a dedicated database login
-- and NOLOGIN responsibility role. The normal runtime cannot SET ROLE into or
-- directly invoke this registration boundary.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_roles WHERE rolname='qiqihar_event_consumer_registrar') THEN
        CREATE ROLE qiqihar_event_consumer_registrar NOLOGIN NOINHERIT;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_roles WHERE rolname='qiqihar_event_consumer_registrar_login') THEN
        CREATE ROLE qiqihar_event_consumer_registrar_login LOGIN NOINHERIT;
    END IF;
END;
$$;

ALTER ROLE qiqihar_event_consumer_registrar NOLOGIN NOINHERIT;
ALTER ROLE qiqihar_event_consumer_registrar_login LOGIN NOINHERIT;

REVOKE qiqihar_enterprise_runtime FROM qiqihar_event_consumer_registrar;
REVOKE qiqihar_enterprise_runtime FROM qiqihar_event_consumer_registrar_login;
REVOKE qiqihar_event_consumer_registrar FROM qiqihar_enterprise_runtime;
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='cofco_app') THEN
        REVOKE qiqihar_event_consumer_registrar FROM cofco_app;
    END IF;
END;
$$;
GRANT qiqihar_event_consumer_registrar TO qiqihar_event_consumer_registrar_login;

GRANT USAGE ON SCHEMA platform TO qiqihar_event_consumer_registrar;

REVOKE ALL ON FUNCTION platform.ensure_business_event_consumer(
    varchar,varchar,bigint,varchar)
FROM PUBLIC,qiqihar_enterprise_runtime,qiqihar_event_operations_monitor,
    qiqihar_master_data_applicant,qiqihar_master_data_reviewer,
    qiqihar_master_data_applier,qiqihar_event_consumer_registrar_login;
GRANT EXECUTE ON FUNCTION platform.ensure_business_event_consumer(
    varchar,varchar,bigint,varchar)
TO qiqihar_event_consumer_registrar;

REVOKE ALL ON FUNCTION platform.ensure_business_event_consumer(varchar,varchar,bigint)
FROM qiqihar_event_consumer_registrar,qiqihar_event_consumer_registrar_login;
REVOKE ALL ON FUNCTION platform.retire_business_event_consumer(
    varchar,varchar,bigint,varchar),
    platform.expire_business_event_consumers()
FROM qiqihar_event_consumer_registrar,qiqihar_event_consumer_registrar_login;

REVOKE ALL ON TABLE platform.business_event_delivery_checkpoint,
    platform.business_event_consumer_lifecycle_event,
    platform.business_event_delivery_backlog,
    platform.business_event_outbox,
    platform.security_user
FROM qiqihar_event_consumer_registrar,qiqihar_event_consumer_registrar_login;

COMMENT ON ROLE qiqihar_event_consumer_registrar IS
    'NOLOGIN responsibility role that may register an already authenticated SSE subject but cannot inspect event or identity tables.';
COMMENT ON ROLE qiqihar_event_consumer_registrar_login IS
    'Dedicated NOINHERIT login for the authenticated SSE registration connection; never shared with the application runtime datasource.';
COMMENT ON FUNCTION platform.ensure_business_event_consumer(
    varchar,varchar,bigint,varchar) IS
    'Dedicated-registrar checkpoint creation and renewal; the shared runtime cannot select an authorization subject.';
