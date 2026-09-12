-- Raise active responsibility cap without changing historical migration checksums.
CREATE OR REPLACE FUNCTION platform.enforce_account_region_limit() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $function$
DECLARE bound_count integer;
BEGIN
 IF NEW.subject_id IS NULL OR platform.account_has_administrator_role(NEW.subject_id) THEN RETURN NEW; END IF;
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
 IF bound_count>10 THEN
  RAISE EXCEPTION 'account_region_limit: at most ten regions per account'
   USING ERRCODE='23514', CONSTRAINT='account_region_limit';
 END IF;
 RETURN NEW;
END;
$function$;
