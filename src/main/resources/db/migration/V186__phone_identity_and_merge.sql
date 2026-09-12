-- Challenges are session/purpose bound; verification codes and credentials are never stored.
CREATE TABLE platform.phone_identity (
 phone varchar(11) PRIMARY KEY CHECK (phone ~ '^1[3-9][0-9]{9}$'),
 subject_id varchar(120) NOT NULL UNIQUE REFERENCES platform.security_user(subject_id),
 verified_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE platform.sms_challenge (
 challenge_id uuid PRIMARY KEY, phone varchar(11) NOT NULL, purpose varchar(20) NOT NULL,
 session_hash varchar(64) NOT NULL, client_hash varchar(64) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(), expires_at timestamptz NOT NULL,
 attempts integer NOT NULL DEFAULT 0, sent boolean NOT NULL DEFAULT false,
 consumed boolean NOT NULL DEFAULT false
);
CREATE INDEX sms_challenge_phone_time ON platform.sms_challenge(phone,created_at);
CREATE INDEX sms_challenge_client_time ON platform.sms_challenge(client_hash,created_at);
CREATE TABLE platform.phone_merge_ticket (
 ticket_id uuid PRIMARY KEY, source_subject varchar(120) NOT NULL REFERENCES platform.security_user,
 target_subject varchar(120) NOT NULL REFERENCES platform.security_user,
 session_hash varchar(64) NOT NULL, snapshot varchar(64) NOT NULL,
 expires_at timestamptz NOT NULL, consumed boolean NOT NULL DEFAULT false
);
CREATE TABLE platform.phone_merge_history (
 merge_id uuid PRIMARY KEY, source_subject varchar(120) NOT NULL REFERENCES platform.security_user,
 target_subject varchar(120) NOT NULL REFERENCES platform.security_user,
 region_choice varchar(10) NOT NULL CHECK(region_choice IN ('PHONE','ORIGINAL')),
 source_regions text NOT NULL, target_regions text NOT NULL, merged_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE platform.phone_identity OWNER TO qiqihar_migration_owner;
ALTER TABLE platform.sms_challenge OWNER TO qiqihar_migration_owner;
ALTER TABLE platform.phone_merge_ticket OWNER TO qiqihar_migration_owner;
ALTER TABLE platform.phone_merge_history OWNER TO qiqihar_migration_owner;
GRANT SELECT,INSERT,UPDATE,DELETE ON platform.phone_identity TO qiqihar_enterprise_runtime;
GRANT SELECT,INSERT,UPDATE ON platform.sms_challenge,platform.phone_merge_ticket,platform.phone_merge_history TO qiqihar_enterprise_runtime;
CREATE OR REPLACE FUNCTION platform.employee_region_available(candidate varchar, target_subject varchar)
RETURNS boolean LANGUAGE sql STABLE SET search_path=pg_catalog AS $function$
WITH occupied AS (
 SELECT region_code,subject_id FROM platform.security_user_region_scope
 WHERE valid_until IS NULL OR valid_until>statement_timestamp()
 UNION SELECT region_code,subject_id FROM platform.region_responsibility WHERE subject_id IS NOT NULL
)
SELECT coalesce(target_subject='admin',false) OR NOT EXISTS (
 SELECT 1 FROM occupied JOIN platform.security_user u USING(subject_id)
 WHERE occupied.region_code=candidate AND u.enabled AND u.account_status='ACTIVE'
 AND u.employment_status='ACTIVE'
 AND (u.termination_effective_at IS NULL OR u.termination_effective_at>statement_timestamp())
 AND occupied.subject_id<>'admin' AND occupied.subject_id IS DISTINCT FROM target_subject
);
$function$;
-- Reactivating an account must not bypass the same exclusive allocation rule.
CREATE FUNCTION platform.enforce_employee_reactivation_regions() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $function$
DECLARE candidate varchar;
BEGIN
 IF NEW.enabled AND NEW.account_status='ACTIVE' AND NEW.employment_status='ACTIVE' THEN
  PERFORM pg_advisory_xact_lock(184,185);
  FOR candidate IN SELECT region_code FROM platform.security_user_region_scope
   WHERE subject_id=NEW.subject_id AND (valid_until IS NULL OR valid_until>statement_timestamp())
   UNION SELECT region_code FROM platform.region_responsibility WHERE subject_id=NEW.subject_id
  LOOP
   IF NOT platform.employee_region_available(candidate,NEW.subject_id) THEN
    RAISE EXCEPTION 'region_binding_exclusive: region is already assigned'
     USING ERRCODE='23505', CONSTRAINT='region_binding_exclusive';
   END IF;
  END LOOP;
 END IF;
 RETURN NEW;
END;
$function$;
ALTER FUNCTION platform.enforce_employee_reactivation_regions() OWNER TO qiqihar_migration_owner;
CREATE TRIGGER employee_reactivation_regions BEFORE UPDATE OF enabled,account_status,employment_status
ON platform.security_user FOR EACH ROW EXECUTE FUNCTION platform.enforce_employee_reactivation_regions();
