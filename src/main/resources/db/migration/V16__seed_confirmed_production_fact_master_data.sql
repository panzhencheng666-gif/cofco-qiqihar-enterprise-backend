CREATE TABLE platform.production_fact_category (
    code varchar(20) PRIMARY KEY,
    label varchar(100) NOT NULL,
    sort_order integer NOT NULL UNIQUE
);

INSERT INTO platform.production_fact_category (code, label, sort_order) VALUES
    ('QUALITY', '质量指标', 10),
    ('COST', '生产成本', 20),
    ('INSURANCE', '农业保险', 30),
    ('SUBSIDY', '农业补贴', 40)
ON CONFLICT (code) DO UPDATE
SET label = EXCLUDED.label, sort_order = EXCLUDED.sort_order;

ALTER TABLE platform.production_fact_definition
    DROP CONSTRAINT production_fact_definition_category_check;

ALTER TABLE platform.production_fact_definition
    ADD CONSTRAINT production_fact_definition_category_fk
    FOREIGN KEY (category) REFERENCES platform.production_fact_category(code);

INSERT INTO platform.production_fact_definition
    (code, category, label, value_type, unit, description, decimal_precision, decimal_scale)
VALUES
    ('MOISTURE', 'QUALITY', '水分', 'DECIMAL', '%', NULL, 18, 1),
    ('TEST_WEIGHT', 'QUALITY', '容重', 'DECIMAL', '克/升', NULL, 18, 0),
    ('IMPURITY', 'QUALITY', '杂质', 'DECIMAL', '%', NULL, 18, 1),
    ('IMPERFECT_GRAIN', 'QUALITY', '不完善粒', 'DECIMAL', '%', NULL, 18, 1),
    ('MILDEW', 'QUALITY', '霉变', 'DECIMAL', '%', NULL, 18, 1),
    ('PROTEIN', 'QUALITY', '蛋白', 'DECIMAL', '%', NULL, 18, 1),
    ('OIL_YIELD', 'QUALITY', '出油率', 'DECIMAL', '%', NULL, 18, 1),
    ('MILLING_YIELD', 'QUALITY', '出米率', 'DECIMAL', '%', NULL, 18, 1),
    ('BROWN_RICE_YIELD', 'QUALITY', '出糙率', 'DECIMAL', '%', NULL, 18, 1),
    ('LAND_RENT', 'COST', '地租', 'DECIMAL', '元/亩', NULL, 18, 0),
    ('SEED_COST', 'COST', '种子费用', 'DECIMAL', '元/亩', NULL, 18, 0),
    ('PESTICIDE_COST', 'COST', '农药费用', 'DECIMAL', '元/亩', NULL, 18, 0),
    ('FERTILIZER_COST', 'COST', '化肥费用', 'DECIMAL', '元/亩', NULL, 18, 0),
    ('IRRIGATION_COST', 'COST', '灌溉费用', 'DECIMAL', '元/亩', NULL, 18, 0),
    ('LABOR_COST', 'COST', '人工费用', 'DECIMAL', '元/亩', NULL, 18, 0),
    ('MACHINERY_COST', 'COST', '机耕费用', 'DECIMAL', '元/亩', NULL, 18, 0),
    ('OTHER_COST', 'COST', '其他成本', 'DECIMAL', '元/亩', NULL, 18, 0),
    ('INSURANCE_AMOUNT', 'INSURANCE', '保险金额', 'DECIMAL', '元', NULL, 18, 0),
    ('SUBSIDY_AMOUNT', 'SUBSIDY', '补贴金额', 'DECIMAL', '元', NULL, 18, 0)
ON CONFLICT (code) DO UPDATE
SET category = EXCLUDED.category,
    label = EXCLUDED.label,
    value_type = EXCLUDED.value_type,
    unit = EXCLUDED.unit,
    description = EXCLUDED.description,
    decimal_precision = EXCLUDED.decimal_precision,
    decimal_scale = EXCLUDED.decimal_scale;

INSERT INTO platform.production_fact_applicability
    (fact_code, product_code, object_type_code, business_domain, page_kind, sort_order)
SELECT quality.fact_code, quality.product_code, object_type.code,
       'PRODUCTION', 'MONITORING', quality.sort_order
FROM (VALUES
    ('CORN', 'MOISTURE', 100),
    ('CORN', 'TEST_WEIGHT', 110),
    ('CORN', 'IMPURITY', 120),
    ('CORN', 'IMPERFECT_GRAIN', 130),
    ('CORN', 'MILDEW', 140),
    ('SOYBEAN', 'PROTEIN', 100),
    ('SOYBEAN', 'OIL_YIELD', 110),
    ('SOYBEAN', 'IMPERFECT_GRAIN', 120),
    ('SOYBEAN', 'MOISTURE', 130),
    ('SOYBEAN', 'IMPURITY', 140),
    ('RICE', 'MOISTURE', 100),
    ('RICE', 'MILLING_YIELD', 110),
    ('RICE', 'BROWN_RICE_YIELD', 120),
    ('RICE', 'IMPURITY', 130)
) AS quality(product_code, fact_code, sort_order)
CROSS JOIN platform.object_type object_type
WHERE object_type.code IN ('FARMER', 'VILLAGE_COMMITTEE', 'AGRICULTURAL_TECH_STATION')
ON CONFLICT DO NOTHING;

INSERT INTO platform.production_fact_applicability
    (fact_code, product_code, object_type_code, business_domain, page_kind, sort_order)
SELECT fact.code, product.code, object_type.code, 'PRODUCTION', 'MONITORING', fact.sort_order
FROM (VALUES
    ('LAND_RENT', 200),
    ('SEED_COST', 210),
    ('PESTICIDE_COST', 220),
    ('FERTILIZER_COST', 230),
    ('IRRIGATION_COST', 240),
    ('LABOR_COST', 250),
    ('MACHINERY_COST', 260),
    ('OTHER_COST', 270),
    ('INSURANCE_AMOUNT', 300),
    ('SUBSIDY_AMOUNT', 400)
) AS fact(code, sort_order)
CROSS JOIN platform.product product
CROSS JOIN platform.object_type object_type
WHERE object_type.code IN ('FARMER', 'VILLAGE_COMMITTEE')
ON CONFLICT DO NOTHING;

COMMENT ON TABLE platform.production_fact_category IS
    'Confirmed production form group metadata; contains configuration/master data only.';
COMMENT ON TABLE platform.production_fact_definition IS
    'Confirmed production fact definitions; contains no production record values.';
