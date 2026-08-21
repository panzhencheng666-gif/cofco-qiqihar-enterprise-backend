-- Business users choose exactly one of two roles. Historical/internal permission bundles remain
-- available to the authorization engine but are not assignable as business roles.

UPDATE platform.access_role SET name='填报员' WHERE code='BUSINESS_OPERATOR';
UPDATE platform.access_role SET name='管理员' WHERE code='BUSINESS_REVIEWER';

-- An administrator can perform the complete reporter workflow and additionally review or return it.
INSERT INTO platform.access_role_permission(role_code,permission_code)
SELECT 'BUSINESS_REVIEWER',permission_code
FROM platform.access_role_permission
WHERE role_code='BUSINESS_OPERATOR'
ON CONFLICT DO NOTHING;
-- Give every existing account one canonical business-role projection without destroying its
-- effective-dated historical grants or internal permission bundles.
WITH classified AS (
    SELECT assignment.subject_id,
           bool_or(assignment.role_code IN (
               'BUSINESS_REVIEWER','SYSTEM_ADMIN','ACCOUNT_OWNER','IDENTITY_ADMIN','ACCESS_REVIEWER')) AS administrator
    FROM platform.security_user_role assignment
    WHERE now()>=assignment.valid_from
      AND (assignment.valid_until IS NULL OR now()<assignment.valid_until)
      AND (assignment.review_due_at IS NULL OR now()<assignment.review_due_at)
    GROUP BY assignment.subject_id
), canonical AS (
    SELECT subject_id,
           CASE WHEN administrator THEN 'BUSINESS_REVIEWER' ELSE 'BUSINESS_OPERATOR' END AS role_code
    FROM classified
)
INSERT INTO platform.security_user_role(
    subject_id,role_code,valid_from,granted_by,granted_at,last_reviewed_at,review_due_at)
SELECT canonical.subject_id,canonical.role_code,now(),canonical.subject_id,now(),now(),now()+interval '1 year'
FROM canonical
WHERE NOT EXISTS (
    SELECT 1
    FROM platform.security_user_role active_assignment
    WHERE active_assignment.subject_id=canonical.subject_id
      AND active_assignment.role_code=canonical.role_code
      AND now()>=active_assignment.valid_from
      AND (active_assignment.valid_until IS NULL OR now()<active_assignment.valid_until)
      AND (active_assignment.review_due_at IS NULL OR now()<active_assignment.review_due_at))
ON CONFLICT DO NOTHING;
