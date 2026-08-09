-- Enterprise identity governance: account/employment lifecycle, positions,
-- effective-dated access grants, and auditable periodic access reviews.

ALTER TABLE platform.security_user
    ADD COLUMN account_status varchar(24) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN employment_status varchar(24) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN activated_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN suspended_at timestamptz,
    ADD COLUMN termination_effective_at timestamptz,
    ADD COLUMN version bigint NOT NULL DEFAULT 0,
    ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now(),
    ADD CONSTRAINT security_user_account_status_check
        CHECK (account_status IN ('INVITED','ACTIVE','LOCKED','SUSPENDED','REVOKED')),
    ADD CONSTRAINT security_user_employment_status_check
        CHECK (employment_status IN ('ACTIVE','LEAVE','TERMINATED')),
    ADD CONSTRAINT security_user_version_check CHECK (version >= 0);

UPDATE platform.security_user
SET account_status = 'SUSPENDED', suspended_at = now()
WHERE NOT enabled;

CREATE TABLE platform.position (
    code varchar(80) PRIMARY KEY,
    name varchar(160) NOT NULL UNIQUE,
    active boolean NOT NULL DEFAULT true,
    sort_order integer NOT NULL UNIQUE,
    CHECK (btrim(code) <> ''),
    CHECK (btrim(name) <> '')
);

CREATE TABLE platform.security_user_position (
    subject_id varchar(120) NOT NULL REFERENCES platform.security_user(subject_id) ON DELETE CASCADE,
    position_code varchar(80) NOT NULL REFERENCES platform.position(code),
    valid_from timestamptz NOT NULL DEFAULT now(),
    valid_until timestamptz,
    primary_position boolean NOT NULL DEFAULT false,
    assigned_by varchar(120) REFERENCES platform.security_user(subject_id),
    assigned_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (subject_id, position_code, valid_from),
    CHECK (valid_until IS NULL OR valid_until > valid_from)
);

ALTER TABLE platform.security_user_role
    ADD COLUMN valid_from timestamptz NOT NULL DEFAULT '-infinity',
    ADD COLUMN valid_until timestamptz,
    ADD COLUMN granted_by varchar(120) REFERENCES platform.security_user(subject_id),
    ADD COLUMN granted_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN last_reviewed_at timestamptz,
    ADD COLUMN review_due_at timestamptz,
    ADD CONSTRAINT security_user_role_validity_check
        CHECK (valid_until IS NULL OR valid_until > valid_from);

ALTER TABLE platform.security_user_region_scope
    ADD COLUMN valid_from timestamptz NOT NULL DEFAULT '-infinity',
    ADD COLUMN valid_until timestamptz,
    ADD COLUMN granted_by varchar(120) REFERENCES platform.security_user(subject_id),
    ADD COLUMN granted_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN last_reviewed_at timestamptz,
    ADD COLUMN review_due_at timestamptz,
    ADD CONSTRAINT security_user_region_scope_validity_check
        CHECK (valid_until IS NULL OR valid_until > valid_from);

CREATE INDEX security_user_role_effective_lookup
    ON platform.security_user_role(subject_id, valid_from, valid_until);
CREATE INDEX security_user_region_scope_effective_lookup
    ON platform.security_user_region_scope(subject_id, valid_from, valid_until);
CREATE INDEX security_user_position_effective_lookup
    ON platform.security_user_position(subject_id, valid_from, valid_until);

CREATE TABLE platform.access_review_campaign (
    review_id uuid PRIMARY KEY,
    name varchar(160) NOT NULL,
    scope_work_unit_code varchar(60) REFERENCES platform.work_unit(code),
    status_code varchar(24) NOT NULL,
    due_at timestamptz NOT NULL,
    created_by varchar(120) NOT NULL REFERENCES platform.security_user(subject_id),
    created_at timestamptz NOT NULL DEFAULT now(),
    completed_by varchar(120) REFERENCES platform.security_user(subject_id),
    completed_at timestamptz,
    CHECK (status_code IN ('OPEN','COMPLETED','CANCELLED')),
    CHECK (btrim(name) <> '')
);

CREATE TABLE platform.access_review_item (
    review_id uuid NOT NULL REFERENCES platform.access_review_campaign(review_id) ON DELETE CASCADE,
    subject_id varchar(120) NOT NULL REFERENCES platform.security_user(subject_id),
    grant_type varchar(24) NOT NULL,
    grant_key varchar(160) NOT NULL,
    decision_code varchar(24) NOT NULL DEFAULT 'PENDING',
    decided_by varchar(120) REFERENCES platform.security_user(subject_id),
    decided_at timestamptz,
    reason varchar(500),
    PRIMARY KEY (review_id, subject_id, grant_type, grant_key),
    CHECK (grant_type IN ('ROLE','REGION','POSITION')),
    CHECK (decision_code IN ('PENDING','RETAIN','REVOKE'))
);

INSERT INTO platform.access_role(code,name,active,sort_order) VALUES
    ('IDENTITY_ADMIN','身份与权限管理员',true,60),
    ('ACCESS_REVIEWER','访问权限复核员',true,70);

INSERT INTO platform.access_permission(code,name,active,sort_order) VALUES
    ('IDENTITY_READ','查看组织与员工',true,110),
    ('IDENTITY_ADMIN','维护组织、员工与授权',true,120),
    ('ACCESS_REVIEW','执行访问权限复核',true,130),
    ('AUDIT_READ','查看不可变审计记录',true,140);

INSERT INTO platform.access_role_permission(role_code,permission_code) VALUES
    ('IDENTITY_ADMIN','IDENTITY_READ'),
    ('IDENTITY_ADMIN','IDENTITY_ADMIN'),
    ('IDENTITY_ADMIN','AUDIT_READ'),
    ('ACCESS_REVIEWER','IDENTITY_READ'),
    ('ACCESS_REVIEWER','ACCESS_REVIEW'),
    ('ACCESS_REVIEWER','AUDIT_READ');

INSERT INTO platform.access_role_permission(role_code,permission_code)
SELECT 'SYSTEM_ADMIN', code
FROM platform.access_permission
WHERE code IN ('IDENTITY_READ','IDENTITY_ADMIN','ACCESS_REVIEW','AUDIT_READ');

COMMENT ON TABLE platform.security_user_position IS
    'Effective-dated employee position assignments; account identity remains owned by the enterprise IdP.';
COMMENT ON TABLE platform.access_review_campaign IS
    'Periodic certification of role, region, and position grants.';
