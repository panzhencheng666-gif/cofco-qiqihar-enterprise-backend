-- All business accounts share one allocation rule; only the literal admin account is exempt.
-- Existing conflicts are preserved for explicit operator resolution, never reassigned by migration.
CREATE FUNCTION platform.employee_region_available(candidate varchar, target_subject varchar)
RETURNS boolean LANGUAGE sql STABLE SET search_path=pg_catalog AS $function$
WITH occupied AS (
 SELECT region_code,subject_id FROM platform.security_user_region_scope
 WHERE valid_until IS NULL OR valid_until>statement_timestamp()
 UNION SELECT region_code,subject_id FROM platform.region_responsibility WHERE subject_id IS NOT NULL
)
SELECT coalesce(target_subject='admin',false) OR NOT EXISTS (
 SELECT 1 FROM occupied WHERE occupied.region_code=candidate
 AND occupied.subject_id<>'admin' AND occupied.subject_id IS DISTINCT FROM target_subject
);
$function$;
ALTER FUNCTION platform.employee_region_available(varchar,varchar) OWNER TO qiqihar_migration_owner;
REVOKE ALL ON FUNCTION platform.employee_region_available(varchar,varchar) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform.employee_region_available(varchar,varchar) TO qiqihar_enterprise_runtime;

CREATE FUNCTION platform.enforce_employee_region_exclusivity() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $function$
BEGIN
 IF NEW.subject_id IS NULL OR NEW.subject_id='admin' THEN RETURN NEW; END IF;
 IF TG_TABLE_NAME='security_user_region_scope' THEN
  IF NEW.valid_until IS NOT NULL AND NEW.valid_until<=statement_timestamp() THEN RETURN NEW; END IF;
 END IF;
 PERFORM pg_advisory_xact_lock(184,185);
 IF NOT platform.employee_region_available(NEW.region_code,NEW.subject_id) THEN
  RAISE EXCEPTION 'region_binding_exclusive: region is already assigned'
   USING ERRCODE='23505', CONSTRAINT='region_binding_exclusive';
 END IF;
 RETURN NEW;
END;
$function$;
ALTER FUNCTION platform.enforce_employee_region_exclusivity() OWNER TO qiqihar_migration_owner;
CREATE TRIGGER employee_region_exclusive BEFORE INSERT OR UPDATE ON platform.security_user_region_scope
FOR EACH ROW EXECUTE FUNCTION platform.enforce_employee_region_exclusivity();
CREATE TRIGGER employee_responsibility_exclusive BEFORE INSERT OR UPDATE ON platform.region_responsibility
FOR EACH ROW EXECUTE FUNCTION platform.enforce_employee_region_exclusivity();
