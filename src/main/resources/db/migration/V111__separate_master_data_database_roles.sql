-- Master-data governance is a database privilege boundary, not an application GUC.
-- The migration owner is deliberately NOLOGIN; runtime and the three workflow
-- entry roles receive only the privileges needed by their respective paths.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='qiqihar_migration_owner') THEN
        CREATE ROLE qiqihar_migration_owner NOLOGIN NOINHERIT;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='qiqihar_enterprise_runtime') THEN
        CREATE ROLE qiqihar_enterprise_runtime NOLOGIN;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='qiqihar_master_data_applicant') THEN
        CREATE ROLE qiqihar_master_data_applicant NOLOGIN;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='qiqihar_master_data_reviewer') THEN
        CREATE ROLE qiqihar_master_data_reviewer NOLOGIN;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='qiqihar_master_data_applier') THEN
        CREATE ROLE qiqihar_master_data_applier NOLOGIN;
    END IF;
    EXECUTE format('GRANT qiqihar_migration_owner TO %I', current_user);
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='cofco_app') THEN
        GRANT qiqihar_enterprise_runtime TO cofco_app;
    END IF;
END;
$$;

GRANT qiqihar_enterprise_runtime TO
    qiqihar_master_data_applicant,
    qiqihar_master_data_reviewer,
    qiqihar_master_data_applier;

-- The runtime group carries normal application persistence privileges. The
-- canonical master-data relations are revoked immediately below and restored
-- as read-only, so this broad declaration cannot reopen the protected surface.
GRANT USAGE ON SCHEMA
    platform,production,market,logistics,supply,reporting,workflow,overview,evidence,registry
TO qiqihar_enterprise_runtime;
GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA
    platform,production,market,logistics,supply,reporting,workflow,overview,evidence,registry
TO qiqihar_enterprise_runtime;
GRANT USAGE,SELECT,UPDATE ON ALL SEQUENCES IN SCHEMA
    platform,production,market,logistics,supply,reporting,workflow,overview,evidence,registry
TO qiqihar_enterprise_runtime;

ALTER TABLE platform.region OWNER TO qiqihar_migration_owner;
ALTER TABLE platform.product OWNER TO qiqihar_migration_owner;
ALTER TABLE platform.object_type OWNER TO qiqihar_migration_owner;
ALTER TABLE registry.sample_point_subject_identity OWNER TO qiqihar_migration_owner;
ALTER TABLE platform.master_data_change_request OWNER TO qiqihar_migration_owner;
ALTER TABLE platform.master_data_change_event OWNER TO qiqihar_migration_owner;
ALTER TABLE platform.master_data_revision OWNER TO qiqihar_migration_owner;
ALTER VIEW platform.canonical_master_data OWNER TO qiqihar_migration_owner;

REVOKE ALL ON TABLE
    platform.region,
    platform.product,
    platform.object_type,
    registry.sample_point_subject_identity,
    platform.master_data_change_request,
    platform.master_data_change_event,
    platform.master_data_revision
FROM PUBLIC, qiqihar_enterprise_runtime, qiqihar_master_data_applicant,
    qiqihar_master_data_reviewer, qiqihar_master_data_applier;
REVOKE ALL ON TABLE platform.canonical_master_data
FROM PUBLIC, qiqihar_enterprise_runtime, qiqihar_master_data_applicant,
    qiqihar_master_data_reviewer, qiqihar_master_data_applier;

GRANT USAGE ON SCHEMA platform,registry TO qiqihar_migration_owner,qiqihar_enterprise_runtime;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA platform,registry TO qiqihar_migration_owner;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA platform,registry TO qiqihar_migration_owner;
-- The migration principal remains able to seed and validate the freshly migrated
-- database even when test/local deployments deliberately reuse that connection.
GRANT USAGE ON SCHEMA platform,registry TO CURRENT_USER;
GRANT SELECT ON TABLE
    platform.region,
    platform.product,
    platform.object_type,
    registry.sample_point_subject_identity,
    platform.master_data_change_request,
    platform.master_data_change_event,
    platform.master_data_revision,
    platform.canonical_master_data
TO qiqihar_enterprise_runtime;
GRANT SELECT ON TABLE
    platform.region,
    platform.product,
    platform.object_type,
    registry.sample_point_subject_identity,
    platform.master_data_change_request,
    platform.master_data_change_event,
    platform.master_data_revision,
    platform.canonical_master_data
TO CURRENT_USER;

-- These legacy signatures accepted caller-supplied audit identities. Retain them
-- only as private implementation functions so rolling upgrades do not need data
-- rewrites, and expose identity-free wrappers below.
REVOKE ALL ON FUNCTION platform.submit_master_data_change(
    varchar,varchar,varchar,jsonb,timestamptz,varchar,text)
FROM PUBLIC, qiqihar_enterprise_runtime, qiqihar_master_data_applicant,
    qiqihar_master_data_reviewer, qiqihar_master_data_applier;
REVOKE ALL ON FUNCTION platform.review_master_data_change(bigint,varchar,varchar,text)
FROM PUBLIC, qiqihar_enterprise_runtime, qiqihar_master_data_applicant,
    qiqihar_master_data_reviewer, qiqihar_master_data_applier;
REVOKE ALL ON FUNCTION platform.apply_master_data_change(bigint,varchar)
FROM PUBLIC, qiqihar_enterprise_runtime, qiqihar_master_data_applicant,
    qiqihar_master_data_reviewer, qiqihar_master_data_applier;
REVOKE ALL ON FUNCTION platform.govern_master_data_change(
    varchar,varchar,varchar,jsonb,timestamptz,varchar,varchar,text)
FROM PUBLIC, qiqihar_enterprise_runtime, qiqihar_master_data_applicant,
    qiqihar_master_data_reviewer, qiqihar_master_data_applier;

ALTER FUNCTION platform.submit_master_data_change(
    varchar,varchar,varchar,jsonb,timestamptz,varchar,text)
OWNER TO qiqihar_migration_owner;
ALTER FUNCTION platform.review_master_data_change(bigint,varchar,varchar,text)
OWNER TO qiqihar_migration_owner;
ALTER FUNCTION platform.apply_master_data_change(bigint,varchar)
OWNER TO qiqihar_migration_owner;
ALTER FUNCTION platform.govern_master_data_change(
    varchar,varchar,varchar,jsonb,timestamptz,varchar,varchar,text)
OWNER TO qiqihar_migration_owner;
ALTER FUNCTION platform.govern_master_data_change(
    varchar,varchar,varchar,jsonb,timestamptz,varchar,varchar,text)
SECURITY DEFINER;
ALTER FUNCTION platform.govern_master_data_change(
    varchar,varchar,varchar,jsonb,timestamptz,varchar,varchar,text)
SET search_path = pg_catalog,platform,registry;

CREATE OR REPLACE FUNCTION platform.submit_master_data_change(
    requested_entity_type varchar,
    requested_entity_key varchar,
    requested_operation varchar,
    requested_snapshot jsonb,
    requested_effective_at timestamptz,
    basis text)
RETURNS bigint
LANGUAGE sql
SECURITY DEFINER
SET search_path = pg_catalog,platform,registry
AS $$
    SELECT platform.submit_master_data_change(
      requested_entity_type,requested_entity_key,requested_operation,requested_snapshot,
      requested_effective_at,session_user::varchar,basis)
$$;
ALTER FUNCTION platform.submit_master_data_change(
    varchar,varchar,varchar,jsonb,timestamptz,text)
OWNER TO qiqihar_migration_owner;

CREATE OR REPLACE FUNCTION platform.review_master_data_change(
    reviewed_request_id bigint,
    decision varchar,
    basis text)
RETURNS boolean
LANGUAGE sql
SECURITY DEFINER
SET search_path = pg_catalog,platform,registry
AS $$
    SELECT platform.review_master_data_change(
      reviewed_request_id,decision,session_user::varchar,basis)
$$;
ALTER FUNCTION platform.review_master_data_change(bigint,varchar,text)
OWNER TO qiqihar_migration_owner;

CREATE OR REPLACE FUNCTION platform.apply_master_data_change(applied_request_id bigint)
RETURNS boolean
LANGUAGE sql
SECURITY DEFINER
SET search_path = pg_catalog,platform,registry
AS $$
    SELECT platform.apply_master_data_change(applied_request_id,session_user::varchar)
$$;
ALTER FUNCTION platform.apply_master_data_change(bigint)
OWNER TO qiqihar_migration_owner;

REVOKE ALL ON FUNCTION platform.submit_master_data_change(
    varchar,varchar,varchar,jsonb,timestamptz,text)
FROM PUBLIC, qiqihar_enterprise_runtime, qiqihar_master_data_reviewer,
    qiqihar_master_data_applier;
REVOKE ALL ON FUNCTION platform.review_master_data_change(bigint,varchar,text)
FROM PUBLIC, qiqihar_enterprise_runtime, qiqihar_master_data_applicant,
    qiqihar_master_data_applier;
REVOKE ALL ON FUNCTION platform.apply_master_data_change(bigint)
FROM PUBLIC, qiqihar_enterprise_runtime, qiqihar_master_data_applicant,
    qiqihar_master_data_reviewer;
GRANT EXECUTE ON FUNCTION platform.submit_master_data_change(
    varchar,varchar,varchar,jsonb,timestamptz,text)
TO qiqihar_master_data_applicant;
GRANT EXECUTE ON FUNCTION platform.review_master_data_change(bigint,varchar,text)
TO qiqihar_master_data_reviewer;
GRANT EXECUTE ON FUNCTION platform.apply_master_data_change(bigint)
TO qiqihar_master_data_applier;

-- A writable custom GUC is only correlation data. A canonical write is accepted
-- solely while executing under the fixed SECURITY DEFINER apply owner.
CREATE OR REPLACE FUNCTION platform.guard_controlled_master_data_apply()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog,platform,registry
AS $$
DECLARE
    request_id_value bigint;
    request_row platform.master_data_change_request%ROWTYPE;
    relation_name varchar(160) := TG_TABLE_SCHEMA || '.' || TG_TABLE_NAME;
    source_snapshot jsonb := CASE WHEN TG_OP='DELETE' THEN to_jsonb(OLD) ELSE to_jsonb(NEW) END;
    stable_key varchar(240);
    authorized_owner name;
BEGIN
    SELECT pg_get_userbyid(proowner) INTO authorized_owner
    FROM pg_proc WHERE oid='platform.apply_master_data_change(bigint)'::regprocedure;
    IF current_user<>authorized_owner THEN
        RAISE EXCEPTION 'canonical master data writes require controlled apply';
    END IF;
    BEGIN
        request_id_value := NULLIF(current_setting('application.master_data_apply_request_id',true),'')::bigint;
    EXCEPTION WHEN invalid_text_representation THEN
        request_id_value := NULL;
    END;
    IF request_id_value IS NULL THEN
        RAISE EXCEPTION 'canonical master data writes require controlled apply';
    END IF;
    SELECT * INTO request_row FROM platform.master_data_change_request
    WHERE request_id=request_id_value FOR UPDATE;
    IF NOT FOUND OR request_row.target_relation<>relation_name OR request_row.operation_code<>TG_OP THEN
        RAISE EXCEPTION 'controlled apply request does not match %.% %',TG_TABLE_SCHEMA,TG_TABLE_NAME,TG_OP;
    END IF;
    IF NOT EXISTS(SELECT 1 FROM platform.master_data_change_event
                  WHERE request_id=request_id_value AND event_type='APPROVED')
       OR EXISTS(SELECT 1 FROM platform.master_data_change_event
                 WHERE request_id=request_id_value AND event_type IN ('REJECTED','APPLIED')) THEN
        RAISE EXCEPTION 'controlled apply request lacks an unused approved review';
    END IF;
    IF clock_timestamp()<request_row.effective_at THEN
        RAISE EXCEPTION 'controlled apply request has not reached effective_at';
    END IF;
    IF source_snapshot<>request_row.target_snapshot THEN
        RAISE EXCEPTION 'controlled apply target snapshot changed after review';
    END IF;
    IF relation_name='registry.sample_point_subject_identity' THEN
        stable_key := (source_snapshot->>'business_domain') || ':' || (source_snapshot->>'subject_id');
    ELSE
        stable_key := source_snapshot->>'code';
    END IF;
    IF stable_key<>request_row.entity_key THEN
        RAISE EXCEPTION 'controlled apply stable target key mismatch';
    END IF;
    RETURN CASE WHEN TG_OP='DELETE' THEN OLD ELSE NEW END;
END;
$$;
ALTER FUNCTION platform.guard_controlled_master_data_apply()
OWNER TO qiqihar_migration_owner;
ALTER FUNCTION platform.capture_master_data_revision()
OWNER TO qiqihar_migration_owner;
ALTER FUNCTION platform.reject_append_only_master_data_mutation()
OWNER TO qiqihar_migration_owner;
REVOKE ALL ON FUNCTION platform.guard_controlled_master_data_apply(),
    platform.capture_master_data_revision(),
    platform.reject_append_only_master_data_mutation()
FROM PUBLIC, qiqihar_enterprise_runtime, qiqihar_master_data_applicant,
    qiqihar_master_data_reviewer, qiqihar_master_data_applier;

COMMENT ON ROLE qiqihar_migration_owner IS
    'NOLOGIN owner for protected master-data relations and SECURITY DEFINER implementations.';
COMMENT ON ROLE qiqihar_enterprise_runtime IS
    'Runtime datasource group: read-only master-data access and no governance event fabrication.';
COMMENT ON FUNCTION platform.submit_master_data_change(
    varchar,varchar,varchar,jsonb,timestamptz,text) IS
    'Applicant-only entry point; requested_by is bound to session_user.';
COMMENT ON FUNCTION platform.review_master_data_change(bigint,varchar,text) IS
    'Reviewer-only entry point; review actor is bound to session_user.';
COMMENT ON FUNCTION platform.apply_master_data_change(bigint) IS
    'Applier-only entry point; apply actor is bound to session_user.';
