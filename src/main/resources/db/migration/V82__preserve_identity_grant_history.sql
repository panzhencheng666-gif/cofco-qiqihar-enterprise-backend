-- Forward-only hardening after identity governance was first exercised locally:
-- invitations have no activation timestamp, and grant revisions retain effective-dated history.

ALTER TABLE platform.security_user
    ALTER COLUMN activated_at DROP NOT NULL;

ALTER TABLE platform.security_user_role
    DROP CONSTRAINT security_user_role_pkey,
    ADD PRIMARY KEY (subject_id, role_code, valid_from);

ALTER TABLE platform.security_user_region_scope
    DROP CONSTRAINT security_user_region_scope_pkey,
    ADD PRIMARY KEY (subject_id, region_code, valid_from);

COMMENT ON COLUMN platform.security_user.activated_at IS
    'First activation time; null while an employee invitation has not been activated.';
