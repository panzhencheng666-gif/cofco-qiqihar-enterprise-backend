INSERT INTO platform.access_permission(code, name, sort_order)
VALUES ('BUSINESS_READ', '读取业务数据', 5);

INSERT INTO platform.access_role_permission(role_code, permission_code)
SELECT code, 'BUSINESS_READ'
FROM platform.access_role;
