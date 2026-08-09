INSERT INTO platform.production_fact_category(code, label, sort_order)
VALUES ('DETAIL', '业务调查明细', 5)
ON CONFLICT (code) DO UPDATE SET label = EXCLUDED.label, sort_order = EXCLUDED.sort_order;

ALTER TABLE platform.production_fact_definition
    DROP CONSTRAINT production_fact_definition_value_type_check;
ALTER TABLE platform.production_fact_definition
    ADD CONSTRAINT production_fact_definition_value_type_check
        CHECK (value_type IN ('DECIMAL', 'TEXT'));

INSERT INTO platform.production_fact_definition
    (code, category, label, value_type, unit, description, decimal_precision, decimal_scale)
VALUES
    ('PROD_SAMPLE_NAME', 'DETAIL', '填报对象', 'TEXT', NULL, '被调查的农户、村委会或农技站名称', 18, 4),
    ('PROD_HARVEST_AREA_MU', 'DETAIL', '预计收获面积', 'DECIMAL', '亩', NULL, 18, 4),
    ('PROD_AFFECTED_AREA_MU', 'DETAIL', '灾损面积', 'DECIMAL', '亩', NULL, 18, 4),
    ('PROD_GROWTH_STATUS', 'DETAIL', '当前长势', 'TEXT', NULL, NULL, 18, 4),
    ('PROD_GROWTH_STAGE', 'DETAIL', '生育阶段', 'TEXT', NULL, NULL, 18, 4),
    ('PROD_OPENING_INVENTORY', 'DETAIL', '期初库存', 'DECIMAL', '吨', NULL, 18, 4),
    ('PROD_SALES_VOLUME', 'DETAIL', '销售数量', 'DECIMAL', '吨', NULL, 18, 4),
    ('PROD_SELF_USE', 'DETAIL', '自用数量', 'DECIMAL', '吨', NULL, 18, 4),
    ('PROD_ENDING_INVENTORY', 'DETAIL', '期末余粮', 'DECIMAL', '吨', NULL, 18, 4),
    ('PROD_INTENDED_AREA_MU', 'DETAIL', '下年度意向面积', 'DECIMAL', '亩', NULL, 18, 4),
    ('PROD_INTENTION_REASON', 'DETAIL', '调整原因', 'TEXT', NULL, NULL, 18, 4),
    ('TOXIN', 'QUALITY', '毒素', 'DECIMAL', '%', NULL, 18, 1)
ON CONFLICT (code) DO UPDATE
SET category = EXCLUDED.category, label = EXCLUDED.label, value_type = EXCLUDED.value_type,
    unit = EXCLUDED.unit, description = EXCLUDED.description,
    decimal_precision = EXCLUDED.decimal_precision, decimal_scale = EXCLUDED.decimal_scale;

INSERT INTO platform.production_fact_applicability
    (fact_code, product_code, object_type_code, business_domain, page_kind, sort_order)
SELECT detail.code, product.code, object_type.code, 'PRODUCTION', 'MONITORING', detail.sort_order
FROM platform.product product
CROSS JOIN platform.object_type object_type
CROSS JOIN (VALUES
    ('PROD_SAMPLE_NAME', 1010), ('PROD_HARVEST_AREA_MU', 1020), ('PROD_AFFECTED_AREA_MU', 1030),
    ('PROD_GROWTH_STATUS', 1040), ('PROD_GROWTH_STAGE', 1050), ('PROD_OPENING_INVENTORY', 1060),
    ('PROD_SALES_VOLUME', 1070), ('PROD_SELF_USE', 1080), ('PROD_ENDING_INVENTORY', 1090),
    ('PROD_INTENDED_AREA_MU', 1100), ('PROD_INTENTION_REASON', 1110)
) AS detail(code, sort_order)
WHERE object_type.code IN ('FARMER', 'VILLAGE_COMMITTEE', 'AGRICULTURAL_TECH_STATION')
ON CONFLICT DO NOTHING;

INSERT INTO platform.production_fact_applicability
    (fact_code, product_code, object_type_code, business_domain, page_kind, sort_order)
SELECT 'TOXIN', 'CORN', object_type.code, 'PRODUCTION', 'MONITORING', 115
FROM platform.object_type object_type
WHERE object_type.code IN ('FARMER', 'VILLAGE_COMMITTEE', 'AGRICULTURAL_TECH_STATION')
ON CONFLICT DO NOTHING;

INSERT INTO platform.field_definition(code, name, value_type)
SELECT code,
       CASE code
           WHEN 'PROD_SAMPLE_NAME' THEN '产情填报对象'
           WHEN 'PROD_HARVEST_AREA_MU' THEN '产情预计收获面积'
           WHEN 'PROD_AFFECTED_AREA_MU' THEN '产情灾损面积'
           WHEN 'PROD_GROWTH_STATUS' THEN '产情当前长势'
           WHEN 'PROD_GROWTH_STAGE' THEN '产情生育阶段'
           WHEN 'PROD_OPENING_INVENTORY' THEN '产情期初库存'
           WHEN 'PROD_SALES_VOLUME' THEN '产情销售数量'
           WHEN 'PROD_SELF_USE' THEN '产情自用数量'
           WHEN 'PROD_ENDING_INVENTORY' THEN '产情期末余粮'
           WHEN 'PROD_INTENDED_AREA_MU' THEN '产情下年度意向面积'
           WHEN 'PROD_INTENTION_REASON' THEN '产情调整原因'
           ELSE label
       END,
       value_type
FROM platform.production_fact_definition
WHERE category = 'DETAIL' OR code = 'TOXIN'
ON CONFLICT (code) DO UPDATE SET name = EXCLUDED.name, value_type = EXCLUDED.value_type;

INSERT INTO platform.page_definition_field(product_code, business_domain, page_kind, field_code, sort_order)
SELECT product.code, 'PRODUCTION', 'MONITORING', detail.code, detail.sort_order
FROM platform.product product
CROSS JOIN (VALUES
    ('PROD_SAMPLE_NAME', 21), ('PROD_HARVEST_AREA_MU', 61), ('PROD_AFFECTED_AREA_MU', 62),
    ('PROD_GROWTH_STATUS', 63), ('PROD_GROWTH_STAGE', 64), ('PROD_OPENING_INVENTORY', 81),
    ('PROD_SALES_VOLUME', 82), ('PROD_SELF_USE', 83), ('PROD_ENDING_INVENTORY', 84),
    ('PROD_INTENDED_AREA_MU', 85), ('PROD_INTENTION_REASON', 86)
) AS detail(code, sort_order)
ON CONFLICT DO NOTHING;

INSERT INTO platform.page_column_group_field(product_code, business_domain, page_kind,
    group_code, field_code, sort_order, unit, description)
SELECT product.code, 'PRODUCTION', 'MONITORING', 'REPORT', detail.code, detail.sort_order,
       detail.unit, '由产情填报保存并在调查表中使用同一字段'
FROM platform.product product
CROSS JOIN (VALUES
    ('PROD_SAMPLE_NAME', 21, NULL), ('PROD_HARVEST_AREA_MU', 61, '亩'),
    ('PROD_AFFECTED_AREA_MU', 62, '亩'), ('PROD_GROWTH_STATUS', 63, NULL),
    ('PROD_GROWTH_STAGE', 64, NULL), ('PROD_OPENING_INVENTORY', 81, '吨'),
    ('PROD_SALES_VOLUME', 82, '吨'), ('PROD_SELF_USE', 83, '吨'),
    ('PROD_ENDING_INVENTORY', 84, '吨'), ('PROD_INTENDED_AREA_MU', 85, '亩'),
    ('PROD_INTENTION_REASON', 86, NULL)
) AS detail(code, sort_order, unit)
ON CONFLICT DO NOTHING;

COMMENT ON TABLE production.production_record_submission_metadata IS
    'Versioned production submission provenance and typed survey-detail values; reporter identity remains server-owned.';
