-- Administrator is a repeatable role, not a special username.
CREATE FUNCTION platform.account_has_administrator_role(subject varchar) RETURNS boolean
LANGUAGE sql STABLE SET search_path=pg_catalog AS $function$
 SELECT EXISTS (SELECT 1 FROM platform.security_user u
 JOIN platform.security_user_role role ON role.subject_id=u.subject_id
 JOIN platform.access_role definition ON definition.code=role.role_code AND definition.active
 WHERE u.subject_id=subject AND u.enabled AND u.account_status='ACTIVE' AND u.employment_status='ACTIVE'
 AND (u.termination_effective_at IS NULL OR u.termination_effective_at>CURRENT_TIMESTAMP)
 AND role.role_code IN ('SYSTEM_ADMIN','BUSINESS_REVIEWER') AND role.valid_from<=CURRENT_TIMESTAMP
 AND (role.valid_until IS NULL OR role.valid_until>CURRENT_TIMESTAMP)
 AND (role.review_due_at IS NULL OR role.review_due_at>CURRENT_TIMESTAMP));
$function$;
ALTER FUNCTION platform.account_has_administrator_role(varchar) OWNER TO qiqihar_migration_owner;
REVOKE ALL ON FUNCTION platform.account_has_administrator_role(varchar) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform.account_has_administrator_role(varchar) TO qiqihar_enterprise_runtime;

INSERT INTO platform.access_role_permission(role_code,permission_code)
SELECT 'BUSINESS_REVIEWER',code FROM platform.access_permission WHERE active ON CONFLICT DO NOTHING;

CREATE OR REPLACE FUNCTION platform.employee_region_available(candidate varchar, target_subject varchar)
RETURNS boolean LANGUAGE sql STABLE SET search_path=pg_catalog AS $function$
WITH occupied AS (
 SELECT region_code,subject_id FROM platform.security_user_region_scope
 WHERE valid_until IS NULL OR valid_until>statement_timestamp()
 UNION SELECT region_code,subject_id FROM platform.region_responsibility WHERE subject_id IS NOT NULL
)
SELECT (coalesce(target_subject='admin',false) OR platform.account_has_administrator_role(target_subject)) OR NOT EXISTS (
 SELECT 1 FROM occupied JOIN platform.security_user u USING(subject_id)
 WHERE occupied.region_code=candidate AND u.enabled AND u.account_status='ACTIVE'
 AND u.employment_status='ACTIVE'
 AND (u.termination_effective_at IS NULL OR u.termination_effective_at>statement_timestamp())
 AND occupied.subject_id<>'admin' AND NOT platform.account_has_administrator_role(occupied.subject_id) AND occupied.subject_id IS DISTINCT FROM target_subject
);
$function$;

CREATE OR REPLACE FUNCTION platform.enforce_account_region_limit() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $function$
DECLARE bound_count integer;
BEGIN
 IF NEW.subject_id IS NULL OR NEW.subject_id='admin' OR platform.account_has_administrator_role(NEW.subject_id) THEN RETURN NEW; END IF;
 IF TG_TABLE_NAME='security_user_region_scope' THEN
  IF NEW.valid_until IS NOT NULL AND NEW.valid_until<=statement_timestamp() THEN RETURN NEW; END IF;
 ELSIF TG_TABLE_NAME='security_user' THEN
  IF NOT NEW.enabled OR NEW.account_status<>'ACTIVE' OR NEW.employment_status<>'ACTIVE' THEN RETURN NEW; END IF;
  IF OLD.enabled AND OLD.account_status='ACTIVE' AND OLD.employment_status='ACTIVE' THEN RETURN NEW; END IF;
 END IF;
 PERFORM pg_advisory_xact_lock(184,185);
 SELECT count(*) INTO bound_count FROM (
  SELECT region_code FROM platform.security_user_region_scope
   WHERE subject_id=NEW.subject_id AND (valid_until IS NULL OR valid_until>statement_timestamp())
  UNION SELECT region_code FROM platform.region_responsibility WHERE subject_id=NEW.subject_id
 ) bound;
 IF bound_count>5 THEN
  RAISE EXCEPTION 'account_region_limit: at most five regions per account'
   USING ERRCODE='23514', CONSTRAINT='account_region_limit';
 END IF;
 RETURN NEW;
END;
$function$;
