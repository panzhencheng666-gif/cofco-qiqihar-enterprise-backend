-- A controlled system administrator must receive every registered permission.
-- Keep this invariant in the database so later feature migrations cannot forget
-- to update the administrator role when they add a permission.

INSERT INTO platform.access_role_permission(role_code, permission_code)
SELECT 'SYSTEM_ADMIN', permission.code
FROM platform.access_permission permission
ON CONFLICT DO NOTHING;

CREATE FUNCTION platform.grant_new_permission_to_system_administrator()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    INSERT INTO platform.access_role_permission(role_code, permission_code)
    VALUES ('SYSTEM_ADMIN', NEW.code)
    ON CONFLICT DO NOTHING;
    RETURN NEW;
END;
$$;

CREATE TRIGGER access_permission_grant_system_administrator
AFTER INSERT ON platform.access_permission
FOR EACH ROW
EXECUTE FUNCTION platform.grant_new_permission_to_system_administrator();

COMMENT ON FUNCTION platform.grant_new_permission_to_system_administrator() IS
    'Keeps the controlled SYSTEM_ADMIN role complete when future permissions are registered.';
