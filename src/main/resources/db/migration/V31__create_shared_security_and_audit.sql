-- Task 9: shared identity, permission, unit/region scope, and immutable audit storage.
-- Business modules refer to these platform records; no module owns a second copy of access policy.

CREATE TABLE platform.work_unit (
    code varchar(60) PRIMARY KEY,
    name varchar(160) NOT NULL UNIQUE,
    active boolean NOT NULL DEFAULT true,
    sort_order integer NOT NULL UNIQUE
);

CREATE TABLE platform.work_unit_region_scope (
    work_unit_code varchar(60) NOT NULL REFERENCES platform.work_unit(code),
    region_code varchar(12) NOT NULL REFERENCES platform.region(code),
    PRIMARY KEY (work_unit_code, region_code)
);

CREATE TABLE platform.security_user (
    subject_id varchar(120) PRIMARY KEY,
    display_name varchar(160) NOT NULL,
    work_unit_code varchar(60) NOT NULL REFERENCES platform.work_unit(code),
    enabled boolean NOT NULL DEFAULT true,
    CHECK (btrim(subject_id) <> ''),
    CHECK (btrim(display_name) <> '')
);

CREATE TABLE platform.access_role (
    code varchar(80) PRIMARY KEY,
    name varchar(160) NOT NULL UNIQUE,
    active boolean NOT NULL DEFAULT true,
    sort_order integer NOT NULL UNIQUE
);

CREATE TABLE platform.access_permission (
    code varchar(100) PRIMARY KEY,
    name varchar(160) NOT NULL UNIQUE,
    active boolean NOT NULL DEFAULT true,
    sort_order integer NOT NULL UNIQUE
);

CREATE TABLE platform.access_role_permission (
    role_code varchar(80) NOT NULL REFERENCES platform.access_role(code),
    permission_code varchar(100) NOT NULL REFERENCES platform.access_permission(code),
    PRIMARY KEY (role_code, permission_code)
);

CREATE TABLE platform.security_user_role (
    subject_id varchar(120) NOT NULL REFERENCES platform.security_user(subject_id),
    role_code varchar(80) NOT NULL REFERENCES platform.access_role(code),
    PRIMARY KEY (subject_id, role_code)
);

CREATE TABLE platform.security_user_region_scope (
    subject_id varchar(120) NOT NULL REFERENCES platform.security_user(subject_id),
    region_code varchar(12) NOT NULL REFERENCES platform.region(code),
    PRIMARY KEY (subject_id, region_code)
);

CREATE TABLE platform.business_audit_event (
    event_id uuid PRIMARY KEY,
    aggregate_type varchar(80) NOT NULL CHECK (btrim(aggregate_type) <> ''),
    aggregate_id varchar(120) NOT NULL CHECK (btrim(aggregate_id) <> ''),
    action_code varchar(100) NOT NULL CHECK (btrim(action_code) <> ''),
    actor_subject_id varchar(120) NOT NULL REFERENCES platform.security_user(subject_id),
    work_unit_code varchar(60) NOT NULL REFERENCES platform.work_unit(code),
    occurred_at timestamptz NOT NULL,
    detail jsonb NOT NULL DEFAULT '{}'::jsonb
);

CREATE INDEX business_audit_event_aggregate_query
    ON platform.business_audit_event (aggregate_type, aggregate_id, occurred_at DESC);
CREATE INDEX business_audit_event_actor_query
    ON platform.business_audit_event (actor_subject_id, occurred_at DESC);

CREATE FUNCTION platform.reject_business_audit_event_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'business audit events are immutable';
END;
$$;

CREATE TRIGGER business_audit_event_immutable
BEFORE UPDATE OR DELETE ON platform.business_audit_event
FOR EACH ROW EXECUTE FUNCTION platform.reject_business_audit_event_mutation();

INSERT INTO platform.access_role(code, name, sort_order) VALUES
    ('REPORTER', '报表专员', 10),
    ('REPORT_PUBLISHER', '报表发布员', 20),
    ('BUSINESS_OPERATOR', '业务填报员', 30),
    ('BUSINESS_REVIEWER', '业务审核员', 40),
    ('SYSTEM_ADMIN', '系统管理员', 50);

INSERT INTO platform.access_permission(code, name, sort_order) VALUES
    ('REPORT_PREVIEW', '生成报告预览', 10),
    ('REPORT_EXPORT', '导出报告', 20),
    ('REPORT_PUBLISH', '发布报告', 30),
    ('BUSINESS_CREATE', '新增业务记录', 40),
    ('BUSINESS_UPDATE', '修改业务记录', 50),
    ('BUSINESS_SUBMIT', '提交业务记录', 60),
    ('BUSINESS_APPROVE', '审核业务记录', 70),
    ('BUSINESS_RETURN', '退回业务记录', 80),
    ('BUSINESS_IMPORT', '导入业务记录', 90);

INSERT INTO platform.access_role_permission(role_code, permission_code)
SELECT role_code, permission_code
FROM (VALUES
    ('REPORTER', 'REPORT_PREVIEW'), ('REPORTER', 'REPORT_EXPORT'),
    ('REPORT_PUBLISHER', 'REPORT_PREVIEW'), ('REPORT_PUBLISHER', 'REPORT_EXPORT'), ('REPORT_PUBLISHER', 'REPORT_PUBLISH'),
    ('BUSINESS_OPERATOR', 'BUSINESS_CREATE'), ('BUSINESS_OPERATOR', 'BUSINESS_UPDATE'), ('BUSINESS_OPERATOR', 'BUSINESS_SUBMIT'), ('BUSINESS_OPERATOR', 'BUSINESS_IMPORT'),
    ('BUSINESS_REVIEWER', 'BUSINESS_APPROVE'), ('BUSINESS_REVIEWER', 'BUSINESS_RETURN')
) AS permission_grant(role_code, permission_code);

INSERT INTO platform.access_role_permission(role_code, permission_code)
SELECT 'SYSTEM_ADMIN', code FROM platform.access_permission;

COMMENT ON TABLE platform.business_audit_event IS
    'Append-only cross-module business audit trail. Database trigger rejects every update and delete.';
