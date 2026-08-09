-- A reported cultivar is survey data, not a master-data mutation. Employees enter the
-- name they observed; governed cultivar codes remain available for later normalization.
INSERT INTO platform.field_definition(code, name, value_type)
VALUES
    ('PROD_CULTIVAR_NAME', '产情具体品种', 'TEXT'),
    ('MKT_CULTIVAR_NAME', '市场具体品种', 'TEXT')
ON CONFLICT (code) DO UPDATE SET name = EXCLUDED.name, value_type = EXCLUDED.value_type;

INSERT INTO platform.market_core_field_definition
    (code, label, control_type, unit, decimal_precision, decimal_scale, sort_order,
     description, domain_binding, capability, required)
VALUES
    ('MKT_CULTIVAR_NAME', '具体品种', 'TEXT', NULL, NULL, NULL, 126,
     '现场填报的具体品种名称，不直接修改主数据', 'EXTENSION', 'GENERIC', false)
ON CONFLICT (code) DO UPDATE
SET label = EXCLUDED.label, control_type = EXCLUDED.control_type,
    sort_order = EXCLUDED.sort_order, description = EXCLUDED.description,
    domain_binding = EXCLUDED.domain_binding, capability = EXCLUDED.capability,
    required = EXCLUDED.required;

INSERT INTO platform.market_core_field_applicability
    (product_code, business_domain, page_kind, field_code, domain_binding)
SELECT product.code, 'MARKET', 'MONITORING', 'MKT_CULTIVAR_NAME', 'EXTENSION'
FROM platform.product product
ON CONFLICT DO NOTHING;

INSERT INTO platform.page_definition_field
    (product_code, business_domain, page_kind, field_code, sort_order)
SELECT product.code, 'MARKET', 'MONITORING', 'MKT_CULTIVAR_NAME', 126
FROM platform.product product
ON CONFLICT DO NOTHING;

INSERT INTO platform.page_column_group_field
    (product_code, business_domain, page_kind, group_code, field_code, sort_order, unit, description)
SELECT product.code, 'MARKET', 'MONITORING', 'MARKET', 'MKT_CULTIVAR_NAME', 126, NULL,
       '由市场填报与通用 XLSX 模板使用同一字段'
FROM platform.product product
ON CONFLICT DO NOTHING;
