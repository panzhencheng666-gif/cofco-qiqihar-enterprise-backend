CREATE OR REPLACE FUNCTION registry.detach_deleted_sample_network_memberships()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path=pg_catalog,registry
AS $function$
BEGIN
    IF OLD.deletion_state='ACTIVE' AND NEW.deletion_state='DELETED' THEN
        UPDATE registry.sample_network_membership
        SET status_code='REMOVED',version=version+1,
            decision_reason='正式样本已由有权用户删除',
            decided_by=NEW.deleted_by,decided_at=NEW.deleted_at
        WHERE sample_point_id=NEW.sample_point_id
          AND status_code<>'REMOVED';
    END IF;
    RETURN NEW;
END;
$function$;

ALTER FUNCTION registry.detach_deleted_sample_network_memberships()
    OWNER TO qiqihar_migration_owner;
REVOKE ALL ON FUNCTION registry.detach_deleted_sample_network_memberships() FROM PUBLIC;

CREATE TRIGGER detach_deleted_sample_network_memberships
AFTER UPDATE OF deletion_state ON registry.sample_point
FOR EACH ROW
EXECUTE FUNCTION registry.detach_deleted_sample_network_memberships();

COMMENT ON FUNCTION registry.detach_deleted_sample_network_memberships() IS
    'Within the authoritative sample deletion transaction, marks every annual-network membership removed while preserving its audit history.';
