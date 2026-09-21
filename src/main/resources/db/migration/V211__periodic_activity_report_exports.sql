INSERT INTO platform.access_permission(code,name,active,sort_order)
VALUES ('ACTIVITY_REPORT_SYSTEM','查看并导出全系统周期总结',true,260)
ON CONFLICT (code) DO UPDATE SET name=EXCLUDED.name,active=true;

INSERT INTO platform.access_role_permission(role_code,permission_code)
VALUES ('SYSTEM_ADMIN','ACTIVITY_REPORT_SYSTEM'),('BUSINESS_REVIEWER','ACTIVITY_REPORT_SYSTEM')
ON CONFLICT DO NOTHING;

CREATE TABLE reporting.activity_report_export (
    export_id uuid PRIMARY KEY,
    report_kind varchar(16) NOT NULL CHECK (report_kind IN ('SYSTEM')),
    period_days integer NOT NULL CHECK (period_days IN (7,30)),
    period_start timestamptz NOT NULL,
    period_end timestamptz NOT NULL,
    event_cutoff timestamptz NOT NULL,
    generated_by varchar(120) NOT NULL REFERENCES platform.security_user(subject_id),
    generated_at timestamptz NOT NULL,
    filename varchar(255) NOT NULL CHECK (btrim(filename)<>''),
    content_type varchar(160) NOT NULL,
    content_sha256 char(64) NOT NULL,
    content_bytes bytea NOT NULL,
    CHECK (period_start < period_end),
    CHECK (event_cutoff >= period_end)
);

CREATE INDEX activity_report_export_actor_time
    ON reporting.activity_report_export(generated_by,generated_at DESC);

GRANT SELECT ON platform.business_audit_event,platform.security_user,platform.work_unit
    TO qiqihar_enterprise_runtime;
GRANT SELECT,INSERT ON reporting.activity_report_export TO qiqihar_enterprise_runtime;
GRANT SELECT,INSERT ON reporting.activity_report_export TO CURRENT_USER;

COMMENT ON TABLE reporting.activity_report_export IS
    'Immutable generated system activity summaries with period, cutoff, digest, actor and DOCX bytes.';
