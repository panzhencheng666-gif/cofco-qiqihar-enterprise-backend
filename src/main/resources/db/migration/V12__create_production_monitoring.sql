CREATE TABLE production.production_record (
    record_id varchar(36) PRIMARY KEY,
    product_code varchar(40) NOT NULL REFERENCES platform.product(code),
    object_type_code varchar(60) NOT NULL,
    region_code varchar(12) NOT NULL REFERENCES platform.region(code),
    cultivar_code varchar(60) REFERENCES platform.cultivar(code),
    survey_date date NOT NULL,
    reported_at timestamptz NOT NULL,
    cultivated_area_mu numeric(18, 4) NOT NULL CHECK (cultivated_area_mu >= 0),
    yield_per_mu_kg numeric(18, 4) NOT NULL CHECK (yield_per_mu_kg >= 0),
    estimated_output_kg numeric(22, 4) GENERATED ALWAYS AS
        (round(cultivated_area_mu * yield_per_mu_kg, 4)) STORED,
    status_code varchar(30) NOT NULL CHECK (status_code IN ('DRAFT', 'PENDING_REVIEW', 'APPROVED', 'RETURNED')),
    return_reason varchar(500),
    last_modified_by varchar(120) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (survey_date <= reported_at::date),
    CHECK ((status_code = 'RETURNED' AND return_reason IS NOT NULL)
        OR (status_code <> 'RETURNED' AND return_reason IS NULL)),
    FOREIGN KEY (product_code, object_type_code)
        REFERENCES platform.product_object_type(product_code, object_type_code)
);

CREATE INDEX production_record_list_idx ON production.production_record
    (product_code, survey_date DESC, record_id);
CREATE INDEX production_record_status_idx ON production.production_record
    (product_code, status_code, survey_date DESC, record_id);

CREATE TABLE production.production_record_quality (
    record_id varchar(36) NOT NULL REFERENCES production.production_record(record_id) ON DELETE CASCADE,
    quality_code varchar(60) NOT NULL REFERENCES platform.field_definition(code),
    value numeric(18, 4) NOT NULL CHECK (value >= 0),
    PRIMARY KEY (record_id, quality_code)
);
CREATE TABLE production.production_record_cost (
    record_id varchar(36) NOT NULL REFERENCES production.production_record(record_id) ON DELETE CASCADE,
    cost_code varchar(60) NOT NULL,
    value numeric(18, 4) NOT NULL CHECK (value >= 0),
    PRIMARY KEY (record_id, cost_code)
);
CREATE TABLE production.production_record_insurance (
    record_id varchar(36) NOT NULL REFERENCES production.production_record(record_id) ON DELETE CASCADE,
    insurance_code varchar(60) NOT NULL,
    value numeric(18, 4) NOT NULL CHECK (value >= 0),
    PRIMARY KEY (record_id, insurance_code)
);
CREATE TABLE production.production_record_subsidy (
    record_id varchar(36) NOT NULL REFERENCES production.production_record(record_id) ON DELETE CASCADE,
    subsidy_code varchar(60) NOT NULL,
    value numeric(18, 4) NOT NULL CHECK (value >= 0),
    PRIMARY KEY (record_id, subsidy_code)
);


INSERT INTO platform.field_definition (code, name, value_type) VALUES
    ('PROD_REGION', '产情地区', 'TEXT'), ('PROD_OBJECT_TYPE', '填报对象', 'TEXT'),
    ('PROD_SURVEY_DATE', '调查日期', 'DATE'), ('PROD_REPORTED_AT', '填报日期', 'DATETIME'),
    ('PROD_CULTIVAR', '品种', 'TEXT'), ('PROD_AREA_MU', '种植面积', 'DECIMAL'),
    ('PROD_YIELD_PER_MU', '亩产', 'DECIMAL'), ('PROD_ESTIMATED_OUTPUT', '预计产量', 'DECIMAL'),
    ('PROD_STATUS', '填报状态', 'TEXT');

INSERT INTO platform.page_definition (product_code, business_domain, page_kind)
SELECT code, 'PRODUCTION', 'MONITORING' FROM platform.product;

INSERT INTO platform.page_definition_field (product_code, business_domain, page_kind, field_code, sort_order)
SELECT product.code, 'PRODUCTION', 'MONITORING', field.code, field.sort_order
FROM platform.product product CROSS JOIN (VALUES
    ('PROD_REGION', 10), ('PROD_OBJECT_TYPE', 20), ('PROD_SURVEY_DATE', 30), ('PROD_REPORTED_AT', 40),
    ('PROD_CULTIVAR', 50), ('PROD_AREA_MU', 60), ('PROD_YIELD_PER_MU', 70),
    ('PROD_ESTIMATED_OUTPUT', 80), ('PROD_STATUS', 90)
) AS field(code, sort_order);

SET CONSTRAINTS ALL DEFERRED;
INSERT INTO platform.page_presentation (product_code, business_domain, page_kind, title)
SELECT code, 'PRODUCTION', 'MONITORING', name || '产情监测' FROM platform.product;
INSERT INTO platform.page_breadcrumb (product_code, business_domain, page_kind, code, label, sort_order)
SELECT code, 'PRODUCTION', 'MONITORING', 'PRODUCTION', '产情监测', 10 FROM platform.product
UNION ALL
SELECT code, 'PRODUCTION', 'MONITORING', 'MONITORING', '产情填报', 20 FROM platform.product;
INSERT INTO platform.page_filter_definition (product_code, business_domain, page_kind, code, label, control_type, placeholder, sort_order)
SELECT product.code, 'PRODUCTION', 'MONITORING', filter.code, filter.label, filter.control_type, filter.placeholder, filter.sort_order
FROM platform.product product CROSS JOIN (VALUES
    ('regionCode', '地区', 'REGION_HIERARCHY', '全部地区', 10),
    ('objectTypeCode', '对象类型', 'SELECT', '全部对象类型', 20),
    ('surveyDate', '调查日期', 'DATE', '请选择调查日期', 30),
    ('status', '状态', 'SELECT', '全部状态', 40)
) AS filter(code, label, control_type, placeholder, sort_order);
INSERT INTO platform.page_filter_option (product_code, business_domain, page_kind, filter_code, value, label, sort_order)
SELECT applicability.product_code, 'PRODUCTION', 'MONITORING', 'objectTypeCode', type.code, type.name, type.sort_order
FROM platform.product_object_type applicability JOIN platform.object_type type ON type.code = applicability.object_type_code
WHERE type.business_domain = 'PRODUCTION';
INSERT INTO platform.page_filter_option (product_code, business_domain, page_kind, filter_code, value, label, sort_order)
SELECT product.code, 'PRODUCTION', 'MONITORING', 'status', status.code, status.label, status.sort_order
FROM platform.product product CROSS JOIN (VALUES
    ('DRAFT', '草稿', 10), ('PENDING_REVIEW', '待审核', 20), ('APPROVED', '已审核', 30), ('RETURNED', '退回补充', 40)
) AS status(code, label, sort_order);
INSERT INTO platform.page_column_group (product_code, business_domain, page_kind, code, label, sort_order)
SELECT code, 'PRODUCTION', 'MONITORING', 'REPORT', '产情填报信息', 10 FROM platform.product;
INSERT INTO platform.page_column_group_field (product_code, business_domain, page_kind, group_code, field_code, sort_order, unit)
SELECT product.code, 'PRODUCTION', 'MONITORING', 'REPORT', field.code, field.sort_order, field.unit
FROM platform.product product CROSS JOIN (VALUES
    ('PROD_REGION', 10, NULL), ('PROD_OBJECT_TYPE', 20, NULL), ('PROD_SURVEY_DATE', 30, NULL),
    ('PROD_REPORTED_AT', 40, NULL), ('PROD_CULTIVAR', 50, NULL), ('PROD_AREA_MU', 60, '亩'),
    ('PROD_YIELD_PER_MU', 70, '公斤/亩'), ('PROD_ESTIMATED_OUTPUT', 80, '公斤'), ('PROD_STATUS', 90, NULL)
) AS field(code, sort_order, unit);
INSERT INTO platform.page_action (product_code, business_domain, page_kind, code, label, action_scope, sort_order)
SELECT product.code, 'PRODUCTION', 'MONITORING', action.code, action.label, action.scope, action.sort_order
FROM platform.product product CROSS JOIN (VALUES
    ('NEW', '新建填报', 'PAGE', 10), ('VIEW', '查看', 'ROW', 20), ('SUBMIT', '提交', 'ROW', 30),
    ('APPROVE', '审核', 'ROW', 40), ('RETURN', '退回', 'ROW', 50)
) AS action(code, label, scope, sort_order);
INSERT INTO platform.page_pagination (product_code, business_domain, page_kind, default_page_size)
SELECT code, 'PRODUCTION', 'MONITORING', 20 FROM platform.product;
INSERT INTO platform.page_size_option (product_code, business_domain, page_kind, page_size, sort_order)
SELECT product.code, 'PRODUCTION', 'MONITORING', size.page_size, size.sort_order
FROM platform.product product CROSS JOIN (VALUES (20, 10), (50, 20), (100, 30)) AS size(page_size, sort_order);

COMMENT ON TABLE production.production_record IS
    'Production facts only; the V12 migration deliberately seeds no production records.';
