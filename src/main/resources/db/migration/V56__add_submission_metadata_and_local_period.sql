-- Local bootstrap configuration only.  Business values remain database-owned;
-- the client must never manufacture products, periods or contact metadata.
INSERT INTO platform.business_period(code, name, starts_on, ends_on, sort_order)
VALUES ('2026-W32', '2026年第32周', DATE '2026-08-03', DATE '2026-08-09', 20260832)
ON CONFLICT (code) DO NOTHING;

CREATE TABLE production.production_record_submission_metadata (
    record_id varchar(36) NOT NULL
        REFERENCES production.production_record(record_id) ON DELETE CASCADE,
    field_code varchar(60) NOT NULL,
    value varchar(500) NOT NULL CHECK (btrim(value) <> ''),
    PRIMARY KEY (record_id, field_code)
);

INSERT INTO platform.field_definition(code, name, value_type) VALUES
    ('PROD_REPORTER_NAME', '填报人', 'TEXT'),
    ('PROD_REPORTER_PHONE', '填报人联系方式', 'TEXT'),
    ('PROD_SAMPLE_CONTACT', '样本点联系方式', 'TEXT'),
    ('PROD_SAMPLE_LATITUDE', '样本点纬度', 'DECIMAL'),
    ('PROD_SAMPLE_LONGITUDE', '样本点经度', 'DECIMAL')
ON CONFLICT (code) DO NOTHING;

INSERT INTO platform.page_definition_field(product_code, business_domain, page_kind, field_code, sort_order)
SELECT product.code, 'PRODUCTION', 'MONITORING', field.code, field.sort_order
FROM platform.product product
CROSS JOIN (VALUES
    ('PROD_REPORTER_NAME', 92), ('PROD_REPORTER_PHONE', 93),
    ('PROD_SAMPLE_CONTACT', 94), ('PROD_SAMPLE_LATITUDE', 95),
    ('PROD_SAMPLE_LONGITUDE', 96)
) AS field(code, sort_order)
ON CONFLICT DO NOTHING;

INSERT INTO platform.page_column_group_field(product_code, business_domain, page_kind,
    group_code, field_code, sort_order, unit, description)
SELECT product.code, 'PRODUCTION', 'MONITORING', 'REPORT', field.code, field.sort_order,
       field.unit, '新建或修改填报时必须由责任人提交；样本点必须可联系且有坐标'
FROM platform.product product
CROSS JOIN (VALUES
    ('PROD_REPORTER_NAME', 92, NULL), ('PROD_REPORTER_PHONE', 93, NULL),
    ('PROD_SAMPLE_CONTACT', 94, NULL), ('PROD_SAMPLE_LATITUDE', 95, '度'),
    ('PROD_SAMPLE_LONGITUDE', 96, '度')
) AS field(code, sort_order, unit)
ON CONFLICT DO NOTHING;

INSERT INTO platform.market_core_field_definition
    (code, label, control_type, unit, decimal_precision, decimal_scale, sort_order,
     description, domain_binding, capability, required)
VALUES
    ('MKT_REPORTER_NAME', '填报人', 'TEXT', NULL, NULL, NULL, 120,
     '填报责任人', 'EXTENSION', 'GENERIC', true),
    ('MKT_REPORTER_PHONE', '填报人联系方式', 'TEXT', NULL, NULL, NULL, 121,
     '填报责任人联系方式', 'EXTENSION', 'GENERIC', true),
    ('MKT_SAMPLE_CONTACT', '样本点联系方式', 'TEXT', NULL, NULL, NULL, 122,
     '被填报样本点联系方式', 'EXTENSION', 'GENERIC', true),
    ('MKT_SAMPLE_LATITUDE', '样本点纬度', 'DECIMAL', '度', 10, 7, 123,
     '样本点纬度，范围 -90 至 90', 'EXTENSION', 'GENERIC', true),
    ('MKT_SAMPLE_LONGITUDE', '样本点经度', 'DECIMAL', '度', 10, 7, 124,
     '样本点经度，范围 -180 至 180', 'EXTENSION', 'GENERIC', true)
ON CONFLICT (code) DO NOTHING;

INSERT INTO platform.field_definition(code, name, value_type) VALUES
    ('MKT_REPORTER_NAME', '市场填报人', 'TEXT'),
    ('MKT_REPORTER_PHONE', '市场填报人联系方式', 'TEXT'),
    ('MKT_SAMPLE_CONTACT', '市场样本点联系方式', 'TEXT'),
    ('MKT_SAMPLE_LATITUDE', '市场样本点纬度', 'DECIMAL'),
    ('MKT_SAMPLE_LONGITUDE', '市场样本点经度', 'DECIMAL')
ON CONFLICT (code) DO NOTHING;

INSERT INTO platform.page_definition_field(product_code, business_domain, page_kind, field_code, sort_order)
SELECT product.code, 'MARKET', 'MONITORING', field.code, field.sort_order
FROM platform.product product
CROSS JOIN (VALUES
    ('MKT_REPORTER_NAME', 120), ('MKT_REPORTER_PHONE', 121),
    ('MKT_SAMPLE_CONTACT', 122), ('MKT_SAMPLE_LATITUDE', 123),
    ('MKT_SAMPLE_LONGITUDE', 124)
) AS field(code, sort_order)
ON CONFLICT DO NOTHING;

INSERT INTO platform.page_column_group_field(product_code, business_domain, page_kind,
    group_code, field_code, sort_order, unit, description)
SELECT product.code, 'MARKET', 'MONITORING', 'MARKET', field.code, field.sort_order,
       field.unit, '填报人、样本点联系方式和坐标为必填元数据'
FROM platform.product product
CROSS JOIN (VALUES
    ('MKT_REPORTER_NAME', 120, NULL), ('MKT_REPORTER_PHONE', 121, NULL),
    ('MKT_SAMPLE_CONTACT', 122, NULL), ('MKT_SAMPLE_LATITUDE', 123, '度'),
    ('MKT_SAMPLE_LONGITUDE', 124, '度')
) AS field(code, sort_order, unit)
ON CONFLICT DO NOTHING;

INSERT INTO platform.market_core_field_applicability(product_code, business_domain, page_kind,
    field_code, domain_binding)
SELECT product.code, 'MARKET', 'MONITORING', field.code, 'EXTENSION'
FROM platform.product product
CROSS JOIN (VALUES
    ('MKT_REPORTER_NAME'), ('MKT_REPORTER_PHONE'), ('MKT_SAMPLE_CONTACT'),
    ('MKT_SAMPLE_LATITUDE'), ('MKT_SAMPLE_LONGITUDE')
) AS field(code)
ON CONFLICT DO NOTHING;

COMMENT ON TABLE production.production_record_submission_metadata IS
    'Database-owned reporter and sample-point metadata. Values are required for new records.';
