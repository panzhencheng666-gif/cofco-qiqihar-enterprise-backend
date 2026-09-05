-- Independent responsibility: access scopes can overlap; each assignable region has one owner.
CREATE TABLE platform.region_responsibility (
    region_code varchar(12) PRIMARY KEY REFERENCES platform.region(code),
    subject_id varchar(120) REFERENCES platform.security_user(subject_id),
    version bigint NOT NULL DEFAULT 0,
    updated_by varchar(120) NOT NULL REFERENCES platform.security_user(subject_id),
    updated_at timestamptz NOT NULL DEFAULT now(),
    reason varchar(500) NOT NULL CHECK (btrim(reason) <> '')
);
ALTER TABLE platform.region_responsibility OWNER TO qiqihar_migration_owner;
GRANT SELECT, INSERT, UPDATE ON platform.region_responsibility TO qiqihar_enterprise_runtime;

-- Short administrative transaction: freeze the preview's catalog, authorization and sample set.
-- Other writers may finish first; deadlock/serialization failures roll back the whole handover.
CREATE FUNCTION platform.lock_region_responsibility_change() RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $function$
BEGIN
    LOCK TABLE platform.region_responsibility IN SHARE ROW EXCLUSIVE MODE;
    LOCK TABLE registry.sample_point IN SHARE ROW EXCLUSIVE MODE;
    LOCK TABLE platform.security_user, platform.security_user_region_scope IN SHARE ROW EXCLUSIVE MODE;
    LOCK TABLE platform.region, platform.work_unit, platform.work_unit_region_scope,
        platform.security_user_role, platform.access_role, platform.access_role_permission,
        platform.access_permission, platform.monitoring_scope_region IN SHARE MODE;
END;
$function$;
ALTER FUNCTION platform.lock_region_responsibility_change() OWNER TO qiqihar_migration_owner;
REVOKE ALL ON FUNCTION platform.lock_region_responsibility_change() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform.lock_region_responsibility_change() TO qiqihar_enterprise_runtime;

CREATE FUNCTION registry.apply_region_responsibility() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $function$
DECLARE responsible varchar;
BEGIN
    IF NEW.deletion_state <> 'ACTIVE' THEN RETURN NEW; END IF;
    WITH RECURSIVE ancestors(code,parent_code) AS (
        SELECT code,parent_code FROM platform.region WHERE code=NEW.region_code
        UNION ALL
        SELECT r.code,r.parent_code FROM platform.region r JOIN ancestors a ON r.code=a.parent_code
    )
    SELECT responsibility.subject_id INTO responsible
    FROM platform.region_responsibility responsibility JOIN ancestors ON ancestors.code=responsibility.region_code
    WHERE responsibility.subject_id IS NOT NULL;
    IF responsible IS NULL THEN RETURN NEW; END IF;
    IF TG_OP='INSERT' THEN
        NEW.maintainer_subject_id=responsible;
    ELSIF NEW.region_code IS DISTINCT FROM OLD.region_code THEN
        NEW.maintainer_subject_id=responsible;
    ELSIF NEW.maintainer_subject_id IS DISTINCT FROM responsible THEN
        RAISE EXCEPTION 'sample_region_responsibility_conflict'
            USING ERRCODE='23514', CONSTRAINT='sample_region_responsibility_conflict';
    END IF;
    RETURN NEW;
END;
$function$;
CREATE TRIGGER sample_region_responsibility_guard
BEFORE INSERT OR UPDATE OF region_code,maintainer_subject_id,deletion_state
ON registry.sample_point FOR EACH ROW EXECUTE FUNCTION registry.apply_region_responsibility();

CREATE FUNCTION platform.region_responsible_subject(requested_region varchar) RETURNS varchar
LANGUAGE sql STABLE SET search_path=pg_catalog AS $function$
    WITH RECURSIVE ancestors(code,parent_code) AS (
        SELECT code,parent_code FROM platform.region WHERE code=requested_region
        UNION ALL SELECT r.code,r.parent_code FROM platform.region r JOIN ancestors a ON r.code=a.parent_code
    )
    SELECT responsibility.subject_id FROM platform.region_responsibility responsibility
    JOIN ancestors ON ancestors.code=responsibility.region_code WHERE responsibility.subject_id IS NOT NULL
$function$;

-- County totals are writable by an ordinary employee only when every township has that owner.
-- NULL means no responsibility has been assigned; empty string means a partial or split county.
CREATE FUNCTION platform.county_reporting_subject(requested_county varchar) RETURNS varchar
LANGUAGE sql STABLE SET search_path=pg_catalog AS $function$
    WITH RECURSIVE descendants(code,administrative_level) AS (
        SELECT code,administrative_level FROM platform.region WHERE parent_code=requested_county
        UNION ALL SELECT r.code,r.administrative_level FROM platform.region r JOIN descendants d ON r.parent_code=d.code
    ), townships AS (
        SELECT code,platform.region_responsible_subject(code) AS subject FROM descendants WHERE administrative_level='TOWNSHIP'
    )
    SELECT CASE WHEN count(*)=0 THEN platform.region_responsible_subject(requested_county)
        WHEN count(subject)=0 THEN NULL
        WHEN count(subject)=count(*) AND count(DISTINCT subject)=1 THEN min(subject)
        ELSE '' END FROM townships
$function$;
ALTER FUNCTION platform.region_responsible_subject(varchar) OWNER TO qiqihar_migration_owner;
ALTER FUNCTION platform.county_reporting_subject(varchar) OWNER TO qiqihar_migration_owner;
REVOKE ALL ON FUNCTION platform.region_responsible_subject(varchar),platform.county_reporting_subject(varchar) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform.region_responsible_subject(varchar),platform.county_reporting_subject(varchar) TO qiqihar_enterprise_runtime;
