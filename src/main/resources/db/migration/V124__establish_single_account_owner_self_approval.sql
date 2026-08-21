-- The platform owner is a singleton governance role. It is intentionally not
-- assignable through employee administration; local bootstrap binds it to the
-- stable employee subject while keeping the visible business name independent.

INSERT INTO platform.access_role(code,name,active,sort_order)
SELECT 'ACCOUNT_OWNER','平台唯一所有者',true,coalesce(max(sort_order),0)+10
FROM platform.access_role
ON CONFLICT (code) DO UPDATE SET name=EXCLUDED.name,active=true;

INSERT INTO platform.access_permission(code,name,active,sort_order)
SELECT 'BUSINESS_SELF_APPROVE','审核本人提交的业务记录',true,coalesce(max(sort_order),0)+10
FROM platform.access_permission
ON CONFLICT (code) DO UPDATE SET name=EXCLUDED.name,active=true;

INSERT INTO platform.access_role_permission(role_code,permission_code)
VALUES ('ACCOUNT_OWNER','BUSINESS_SELF_APPROVE')
ON CONFLICT DO NOTHING;

CREATE UNIQUE INDEX security_user_role_single_account_owner
    ON platform.security_user_role(role_code)
    WHERE role_code='ACCOUNT_OWNER';

UPDATE platform.security_user
SET display_name='吴雨桐',updated_at=now(),version=version+1
WHERE subject_id='wang-yang' AND display_name<>'吴雨桐';

COMMENT ON INDEX platform.security_user_role_single_account_owner IS
    'At most one stable employee subject may hold the platform owner role.';
