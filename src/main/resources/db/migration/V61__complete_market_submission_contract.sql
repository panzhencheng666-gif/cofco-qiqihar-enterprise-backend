-- MARKET object category and concrete reported object are separate concepts.
-- Keep the existing stable contact codes while making their user-facing roles explicit.
UPDATE platform.market_core_field_definition
SET label = '填报对象/客户联系方式',
    description = '被填报企业、门店、样本点或客户的联系方式'
WHERE code = 'MKT_SAMPLE_CONTACT';

UPDATE platform.field_definition
SET name = '市场填报对象/客户联系方式'
WHERE code = 'MKT_SAMPLE_CONTACT';

INSERT INTO platform.market_core_field_definition
    (code, label, control_type, unit, decimal_precision, decimal_scale, sort_order,
     description, domain_binding, capability, required)
VALUES
    ('MKT_SAMPLE_NAME', '填报对象/客户名称', 'TEXT', NULL, NULL, NULL, 125,
     '具体企业、门店、样本点或客户名称', 'EXTENSION', 'GENERIC', true);

INSERT INTO platform.field_definition(code, name, value_type)
VALUES ('MKT_SAMPLE_NAME', '市场填报对象/客户名称', 'TEXT');

INSERT INTO platform.page_definition_field
    (product_code, business_domain, page_kind, field_code, sort_order)
SELECT product.code, 'MARKET', 'MONITORING', 'MKT_SAMPLE_NAME', 125
FROM platform.product product;

INSERT INTO platform.page_column_group_field
    (product_code, business_domain, page_kind, group_code, field_code, sort_order, unit, description)
SELECT product.code, 'MARKET', 'MONITORING', 'MARKET', 'MKT_SAMPLE_NAME', 125, NULL,
       '具体填报对象/客户名称，与对象类别分开填报'
FROM platform.product product;

INSERT INTO platform.market_core_field_applicability
    (product_code, business_domain, page_kind, field_code, domain_binding)
SELECT product.code, 'MARKET', 'MONITORING', 'MKT_SAMPLE_NAME', 'EXTENSION'
FROM platform.product product;
