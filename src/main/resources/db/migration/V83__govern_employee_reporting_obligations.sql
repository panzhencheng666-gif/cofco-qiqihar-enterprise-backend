ALTER TABLE workflow.work_item
    ADD COLUMN owner_subject_id varchar(120) REFERENCES platform.security_user(subject_id),
    ADD COLUMN owner_work_unit_code varchar(80) REFERENCES platform.work_unit(code);

WITH source_owner AS (
    SELECT source.record_id,
           COALESCE((
               SELECT audit.actor_subject_id
               FROM platform.business_audit_event audit
               WHERE audit.aggregate_type='PRODUCTION_RECORD'
                 AND audit.aggregate_id=source.record_id
                 AND audit.action_code IN ('PRODUCTION_RECORD_CREATED','PRODUCTION_RECORD_IMPORTED')
               ORDER BY audit.occurred_at,audit.event_id LIMIT 1
           ),source.last_modified_by) AS subject_id
    FROM production.production_record source
)
UPDATE workflow.work_item item
SET owner_subject_id = source_owner.subject_id,
    owner_work_unit_code = security_user.work_unit_code
FROM source_owner
LEFT JOIN platform.security_user ON security_user.subject_id = source_owner.subject_id
WHERE item.source_type = 'PRODUCTION'
  AND item.source_id = source_owner.record_id;

WITH source_owner AS (
    SELECT source.record_id,
           COALESCE((
               SELECT audit.actor_subject_id
               FROM platform.business_audit_event audit
               WHERE audit.aggregate_type='MARKET_RECORD'
                 AND audit.aggregate_id=source.record_id
                 AND audit.action_code IN ('MARKET_RECORD_CREATED','MARKET_RECORD_IMPORTED')
               ORDER BY audit.occurred_at,audit.event_id LIMIT 1
           ),source.last_modified_by) AS subject_id
    FROM market.market_record source
)
UPDATE workflow.work_item item
SET owner_subject_id = source_owner.subject_id,
    owner_work_unit_code = security_user.work_unit_code
FROM source_owner
LEFT JOIN platform.security_user ON security_user.subject_id = source_owner.subject_id
WHERE item.source_type = 'MARKET'
  AND item.source_id = source_owner.record_id;

UPDATE workflow.work_item item
SET owner_subject_id = source.created_by,
    owner_work_unit_code = security_user.work_unit_code
FROM logistics.route_event source
LEFT JOIN platform.security_user ON security_user.subject_id = source.created_by
WHERE item.source_type = 'LOGISTICS'
  AND item.source_id = source.event_id::text;

CREATE INDEX work_item_obligation_weekly_query
    ON workflow.work_item (owner_work_unit_code, owner_subject_id, due_at, region_code, business_domain);

CREATE TABLE workflow.obligation_report_export (
    export_id uuid PRIMARY KEY,
    week_start date NOT NULL,
    week_end date NOT NULL,
    subject_id varchar(120) REFERENCES platform.security_user(subject_id),
    work_unit_code varchar(80) REFERENCES platform.work_unit(code),
    business_domain varchar(30),
    region_code varchar(12) REFERENCES platform.region(code),
    generated_by varchar(120) NOT NULL REFERENCES platform.security_user(subject_id),
    generated_at timestamptz NOT NULL,
    filename varchar(240) NOT NULL,
    content_type varchar(160) NOT NULL,
    content_sha256 char(64) NOT NULL,
    content bytea NOT NULL,
    CHECK (week_end = week_start + 6),
    CHECK (btrim(filename) <> ''),
    CHECK (octet_length(content) > 0)
);

CREATE INDEX obligation_report_export_actor_time
    ON workflow.obligation_report_export (generated_by, generated_at DESC);

INSERT INTO platform.access_permission(code,name,active,sort_order) VALUES
    ('OBLIGATION_REPORT_READ','查看个人填报履职记录',true,150),
    ('OBLIGATION_REPORT_UNIT','查看本单位填报履职记录',true,160),
    ('OBLIGATION_REPORT_EXPORT','导出填报履职周报',true,170);

INSERT INTO platform.access_role_permission(role_code,permission_code)
SELECT role_permission.role_code,'OBLIGATION_REPORT_READ'
FROM platform.access_role_permission role_permission
WHERE role_permission.permission_code='BUSINESS_READ'
ON CONFLICT DO NOTHING;

INSERT INTO platform.access_role_permission(role_code,permission_code)
SELECT role_permission.role_code,'OBLIGATION_REPORT_EXPORT'
FROM platform.access_role_permission role_permission
WHERE role_permission.permission_code='BUSINESS_READ'
ON CONFLICT DO NOTHING;

INSERT INTO platform.access_role_permission(role_code,permission_code) VALUES
    ('BUSINESS_REVIEWER','OBLIGATION_REPORT_UNIT'),
    ('BUSINESS_REVIEWER','OBLIGATION_REPORT_EXPORT'),
    ('IDENTITY_ADMIN','OBLIGATION_REPORT_UNIT'),
    ('IDENTITY_ADMIN','OBLIGATION_REPORT_EXPORT'),
    ('ACCESS_REVIEWER','OBLIGATION_REPORT_UNIT'),
    ('ACCESS_REVIEWER','OBLIGATION_REPORT_EXPORT'),
    ('SYSTEM_ADMIN','OBLIGATION_REPORT_UNIT'),
    ('SYSTEM_ADMIN','OBLIGATION_REPORT_EXPORT')
ON CONFLICT DO NOTHING;

COMMENT ON COLUMN workflow.work_item.owner_subject_id IS
    'Employee accountable for the original business submission; stable when review responsibility changes.';
COMMENT ON COLUMN workflow.work_item.owner_work_unit_code IS
    'Work unit snapshot used for governed employee reporting-obligation summaries.';
COMMENT ON TABLE workflow.obligation_report_export IS
    'Immutable, permission-scoped employee reporting-obligation workbooks with reproducible filters and checksum.';
