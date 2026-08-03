-- Task 8: report definitions, immutable approved-data previews, exports, and publication audit.
-- Definitions are platform data; browser clients only consume the resource exposed by the reporting API.

CREATE TABLE reporting.report_definition (
    report_definition_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code varchar(80) NOT NULL UNIQUE,
    name varchar(160) NOT NULL UNIQUE,
    business_domain varchar(30) NOT NULL CHECK (business_domain IN ('PRODUCTION','MARKET','LOGISTICS','SUPPLY','SUBMISSION','COMPREHENSIVE')),
    business_subtype varchar(80) NOT NULL,
    frequency_code varchar(20) NOT NULL CHECK (frequency_code IN ('DAILY','WEEKLY','MONTHLY')),
    version_no integer NOT NULL CHECK (version_no > 0),
    active boolean NOT NULL DEFAULT true,
    sort_order integer NOT NULL UNIQUE
);

CREATE TABLE reporting.report_definition_section (
    report_definition_id bigint NOT NULL REFERENCES reporting.report_definition(report_definition_id) ON DELETE CASCADE,
    section_code varchar(80) NOT NULL,
    title varchar(160) NOT NULL,
    sort_order integer NOT NULL,
    PRIMARY KEY(report_definition_id, section_code),
    UNIQUE(report_definition_id, sort_order)
);

CREATE TABLE reporting.report_output_format (
    format_code varchar(20) PRIMARY KEY CHECK (format_code IN ('CSV','PDF','DOCX','XLSX')),
    label varchar(80) NOT NULL UNIQUE,
    media_type varchar(120) NOT NULL UNIQUE,
    enabled boolean NOT NULL DEFAULT true,
    sort_order integer NOT NULL UNIQUE
);

CREATE TABLE reporting.approved_dataset (
    dataset_id uuid PRIMARY KEY,
    report_definition_id bigint NOT NULL REFERENCES reporting.report_definition(report_definition_id),
    product_code varchar(40) NOT NULL REFERENCES platform.product(code),
    cultivar_code varchar(80),
    region_level varchar(30) NOT NULL,
    region_code varchar(12) NOT NULL REFERENCES platform.region(code),
    period_code varchar(80) NOT NULL CHECK (btrim(period_code) <> ''),
    frequency_code varchar(20) NOT NULL CHECK (frequency_code IN ('DAILY','WEEKLY','MONTHLY')),
    source_state varchar(30) NOT NULL CHECK (source_state = 'APPROVED'),
    source_summary jsonb NOT NULL,
    immutable_digest varchar(128) NOT NULL CHECK (btrim(immutable_digest) <> ''),
    captured_at timestamptz NOT NULL,
    captured_by varchar(120) NOT NULL CHECK (btrim(captured_by) <> ''),
    UNIQUE(report_definition_id, product_code, cultivar_code, region_code, period_code, frequency_code, immutable_digest)
);

CREATE TABLE reporting.report_preview (
    preview_id uuid PRIMARY KEY,
    report_definition_id bigint NOT NULL REFERENCES reporting.report_definition(report_definition_id),
    dataset_id uuid NOT NULL REFERENCES reporting.approved_dataset(dataset_id),
    parameter_snapshot jsonb NOT NULL,
    content_snapshot jsonb NOT NULL,
    content_digest varchar(128) NOT NULL CHECK (btrim(content_digest) <> ''),
    created_by varchar(120) NOT NULL CHECK (btrim(created_by) <> ''),
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    CHECK (expires_at > created_at)
);

CREATE TABLE reporting.report_export_task (
    export_task_id uuid PRIMARY KEY,
    preview_id uuid NOT NULL REFERENCES reporting.report_preview(preview_id),
    format_code varchar(20) NOT NULL REFERENCES reporting.report_output_format(format_code),
    status_code varchar(20) NOT NULL CHECK (status_code IN ('COMPLETED','FAILED')),
    filename varchar(300) NOT NULL CHECK (btrim(filename) <> ''),
    content_type varchar(120) NOT NULL CHECK (btrim(content_type) <> ''),
    content_digest varchar(128) NOT NULL CHECK (btrim(content_digest) <> ''),
    content bytea NOT NULL,
    requested_by varchar(120) NOT NULL CHECK (btrim(requested_by) <> ''),
    requested_at timestamptz NOT NULL,
    UNIQUE(preview_id, format_code, content_digest)
);

CREATE TABLE reporting.report_publication (
    publication_id uuid PRIMARY KEY,
    preview_id uuid NOT NULL UNIQUE REFERENCES reporting.report_preview(preview_id),
    export_task_id uuid NOT NULL REFERENCES reporting.report_export_task(export_task_id),
    published_by varchar(120) NOT NULL CHECK (btrim(published_by) <> ''),
    published_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0)
);

CREATE TABLE reporting.report_audit_event (
    audit_event_id uuid PRIMARY KEY,
    aggregate_type varchar(30) NOT NULL CHECK (aggregate_type IN ('PREVIEW','EXPORT','PUBLICATION')),
    aggregate_id uuid NOT NULL,
    action_code varchar(30) NOT NULL CHECK (action_code IN ('PREVIEWED','EXPORTED','PUBLISHED')),
    actor varchar(120) NOT NULL CHECK (btrim(actor) <> ''),
    occurred_at timestamptz NOT NULL,
    detail jsonb NOT NULL
);
CREATE INDEX report_preview_definition_query ON reporting.report_preview(report_definition_id, created_at DESC);
CREATE INDEX report_audit_aggregate_query ON reporting.report_audit_event(aggregate_type, aggregate_id, occurred_at);

INSERT INTO reporting.report_definition(code,name,business_domain,business_subtype,frequency_code,version_no,sort_order) VALUES
 ('PRODUCTION_DAILY','产情日报','PRODUCTION','MONITORING','DAILY',1,10),
 ('MARKET_DAILY','市场日报','MARKET','MONITORING','DAILY',1,20),
 ('LOGISTICS_WEEKLY','物流周报','LOGISTICS','MONITORING','WEEKLY',1,30),
 ('SUPPLY_MONTHLY','供需月报','SUPPLY','BALANCE','MONTHLY',1,40),
 ('SUBMISSION_WEEKLY','填报记录周报','SUBMISSION','RECORD','WEEKLY',1,50),
 ('SUBMISSION_MONTHLY','填报记录月报','SUBMISSION','RECORD','MONTHLY',1,60),
 ('COMPREHENSIVE_MONTHLY','综合经营月报','COMPREHENSIVE','MANAGEMENT','MONTHLY',1,70);

INSERT INTO reporting.report_definition_section(report_definition_id,section_code,title,sort_order)
SELECT definition.report_definition_id, section.code, section.title, section.sort_order
FROM reporting.report_definition definition
CROSS JOIN (VALUES
 ('OVERVIEW','总体概览',10),('APPROVED_DATA','核定数据',20),('ANALYSIS','分析说明',30)
) section(code,title,sort_order);

INSERT INTO reporting.report_output_format(format_code,label,media_type,enabled,sort_order) VALUES
 ('CSV','CSV（中文列名）','text/csv;charset=utf-8',true,10),
 ('PDF','PDF','application/pdf',false,20),
 ('DOCX','Word','application/vnd.openxmlformats-officedocument.wordprocessingml.document',false,30),
 ('XLSX','Excel','application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',false,40);

COMMENT ON TABLE reporting.approved_dataset IS
    'Immutable server-side snapshot constructed only from approved/formal business facts; never accepts browser values.';
COMMENT ON TABLE reporting.report_preview IS
    'Preview is a time-limited immutable content and parameter snapshot required before any export.';
COMMENT ON TABLE reporting.report_publication IS
    'Publication refers to an already completed export and never mutates an earlier preview or export.';
