-- Formal market records keep their own metadata contract. This migration does not
-- read or reference the year-independent design-sample metadata tables.

SELECT platform.govern_master_data_change(
    'OBJECT_TYPE',
    'AGRICULTURAL_INPUT_STORE',
    'INSERT',
    jsonb_build_object(
        'code', 'AGRICULTURAL_INPUT_STORE',
        'name', '农资店',
        'business_domain', 'MARKET',
        'sort_order', 180,
        'overview_enabled', true,
        'overview_icon_key', 'agricultural-input-store'),
    clock_timestamp(),
    'V161_MIGRATION_APPLICANT',
    'V161_MIGRATION_REVIEWER',
    '正式市场对象字段合同补齐');

INSERT INTO platform.product_object_type(product_code, object_type_code)
SELECT code, 'AGRICULTURAL_INPUT_STORE' FROM platform.product;

INSERT INTO platform.page_filter_option(
    product_code, business_domain, page_kind, filter_code,
    value, label, sort_order)
SELECT code, 'MARKET', 'MONITORING', 'objectTypeCode',
       'AGRICULTURAL_INPUT_STORE', '农资店', 180
FROM platform.product;

INSERT INTO platform.market_core_field_definition(
    code, label, control_type, unit, decimal_precision, decimal_scale,
    sort_order, description, domain_binding, capability, required)
VALUES
    ('AGRI_INPUT_SEED_SALES_VOLUME', '种子销售量', 'DECIMAL', '公斤', 18, 4,
     130, '所选作物种子的本期销售量', 'EXTENSION', 'GENERIC', false),
    ('AGRI_INPUT_SEED_RETAIL_PRICE', '种子零售价', 'DECIMAL', '元/公斤', 18, 4,
     131, '所选作物种子的零售价', 'EXTENSION', 'GENERIC', false),
    ('AGRI_INPUT_SUPPLY_STATUS', '供货状态', 'SELECT', NULL, NULL, NULL,
     132, '所选作物种子的供货状态', 'EXTENSION', 'GENERIC', false),
    ('AGRI_INPUT_PLANTING_INTENTION_TREND', '种植意向趋势', 'SELECT', NULL, NULL, NULL,
     133, '顾客对所选作物的种植意向趋势', 'EXTENSION', 'GENERIC', false);

INSERT INTO platform.market_core_field_option(field_code, value, label, sort_order)
VALUES
    ('AGRI_INPUT_SUPPLY_STATUS', 'SUFFICIENT', '充足', 10),
    ('AGRI_INPUT_SUPPLY_STATUS', 'NORMAL', '正常', 20),
    ('AGRI_INPUT_SUPPLY_STATUS', 'TIGHT', '偏紧', 30),
    ('AGRI_INPUT_SUPPLY_STATUS', 'OUT_OF_STOCK', '缺货', 40),
    ('AGRI_INPUT_PLANTING_INTENTION_TREND', 'INCREASE', '增加', 10),
    ('AGRI_INPUT_PLANTING_INTENTION_TREND', 'STABLE', '持平', 20),
    ('AGRI_INPUT_PLANTING_INTENTION_TREND', 'DECREASE', '减少', 30);

INSERT INTO platform.field_definition(code, name, value_type)
VALUES
    ('AGRI_INPUT_SEED_SALES_VOLUME', '农资店种子销售量', 'DECIMAL'),
    ('AGRI_INPUT_SEED_RETAIL_PRICE', '农资店种子零售价', 'DECIMAL'),
    ('AGRI_INPUT_SUPPLY_STATUS', '农资店供货状态', 'TEXT'),
    ('AGRI_INPUT_PLANTING_INTENTION_TREND', '农资店种植意向趋势', 'TEXT');

INSERT INTO platform.page_definition_field(
    product_code, business_domain, page_kind, field_code, sort_order)
SELECT product.code, 'MARKET', 'MONITORING', field.code, field.sort_order
FROM platform.product product
CROSS JOIN (VALUES
    ('AGRI_INPUT_SEED_SALES_VOLUME', 130),
    ('AGRI_INPUT_SEED_RETAIL_PRICE', 131),
    ('AGRI_INPUT_SUPPLY_STATUS', 132),
    ('AGRI_INPUT_PLANTING_INTENTION_TREND', 133)
) field(code, sort_order);

INSERT INTO platform.market_core_field_applicability(
    product_code, business_domain, page_kind, field_code, domain_binding)
SELECT product.code, 'MARKET', 'MONITORING', field.code, 'EXTENSION'
FROM platform.product product
CROSS JOIN (VALUES
    ('AGRI_INPUT_SEED_SALES_VOLUME'),
    ('AGRI_INPUT_SEED_RETAIL_PRICE'),
    ('AGRI_INPUT_SUPPLY_STATUS'),
    ('AGRI_INPUT_PLANTING_INTENTION_TREND')
) field(code);

INSERT INTO platform.page_column_group_field(
    product_code, business_domain, page_kind, group_code,
    field_code, sort_order, unit, description)
SELECT product.code, 'MARKET', 'MONITORING', 'MARKET',
       field.code, field.sort_order, field.unit, field.description
FROM platform.product product
CROSS JOIN (VALUES
    ('AGRI_INPUT_SEED_SALES_VOLUME', 130, '公斤', '所选作物种子的本期销售量'),
    ('AGRI_INPUT_SEED_RETAIL_PRICE', 131, '元/公斤', '所选作物种子的零售价'),
    ('AGRI_INPUT_SUPPLY_STATUS', 132, NULL, '所选作物种子的供货状态'),
    ('AGRI_INPUT_PLANTING_INTENTION_TREND', 133, NULL, '顾客对所选作物的种植意向趋势')
) field(code, sort_order, unit, description);

-- Agricultural-input records expose their own observations, never generic grain
-- trade prices or price components.
INSERT INTO platform.market_core_field_object_exclusion(
    product_code, object_type_code, field_code)
SELECT product.code, 'AGRICULTURAL_INPUT_STORE', field.code
FROM platform.product product
CROSS JOIN (VALUES
    ('MKT_PURCHASE_BASE_PRICE'), ('MKT_SALE_BASE_PRICE'),
    ('MKT_CARRIAGE_BOARD_AMOUNT'), ('MKT_PACKAGING_FORM'),
    ('MKT_PACKAGING_AMOUNT'), ('MKT_FREIGHT_AMOUNT')
) field(code);

-- The agricultural-input fields are mounted in the shared formal definition graph
-- but are applicable only to their owning object type.
INSERT INTO platform.market_core_field_object_exclusion(
    product_code, object_type_code, field_code)
SELECT applicability.product_code, applicability.object_type_code, field.code
FROM platform.product_object_type applicability
JOIN platform.object_type object_type
  ON object_type.code = applicability.object_type_code
CROSS JOIN (VALUES
    ('AGRI_INPUT_SEED_SALES_VOLUME'),
    ('AGRI_INPUT_SEED_RETAIL_PRICE'),
    ('AGRI_INPUT_SUPPLY_STATUS'),
    ('AGRI_INPUT_PLANTING_INTENTION_TREND')
) field(code)
WHERE object_type.business_domain = 'MARKET'
  AND object_type.code <> 'AGRICULTURAL_INPUT_STORE';

ALTER TABLE market.market_record DROP COLUMN actual_trade_price;
ALTER TABLE market.market_record
    ALTER COLUMN trade_direction TYPE varchar(20),
    ALTER COLUMN carriage_board_amount DROP NOT NULL,
    ALTER COLUMN packaging_amount DROP NOT NULL,
    ALTER COLUMN freight_amount DROP NOT NULL;

ALTER TABLE market.market_record
    DROP CONSTRAINT market_record_trade_direction_check,
    DROP CONSTRAINT market_record_price_model_check,
    ADD CONSTRAINT market_record_trade_direction_check
        CHECK (trade_direction IN ('PURCHASE', 'SALE', 'BOTH', 'OBSERVATION')),
    ADD CONSTRAINT market_record_price_model_check CHECK (
        (object_type_code <> 'AGRICULTURAL_INPUT_STORE'
            AND carriage_board_amount IS NOT NULL
            AND packaging_amount IS NOT NULL
            AND freight_amount IS NOT NULL
            AND ((trade_direction = 'PURCHASE' AND purchase_base_price IS NOT NULL)
              OR (trade_direction = 'SALE' AND sale_base_price IS NOT NULL)
              OR (trade_direction = 'BOTH'
                AND purchase_base_price IS NOT NULL AND sale_base_price IS NOT NULL)))
        OR (trade_direction = 'OBSERVATION'
            AND object_type_code = 'AGRICULTURAL_INPUT_STORE'
            AND purchase_base_price IS NULL AND sale_base_price IS NULL
            AND carriage_board_amount IS NULL AND packaging_amount IS NULL
            AND freight_amount IS NULL AND packaging_form IS NULL));

ALTER TABLE market.market_record
    ADD COLUMN actual_trade_price numeric(18, 4) GENERATED ALWAYS AS (
        CASE WHEN trade_direction = 'OBSERVATION' THEN NULL ELSE
          round((CASE
              WHEN trade_direction = 'PURCHASE' THEN purchase_base_price
              WHEN trade_direction = 'SALE' THEN sale_base_price
              ELSE (purchase_base_price + sale_base_price) / 2
            END) + carriage_board_amount + packaging_amount + freight_amount, 4)
        END) STORED;

COMMENT ON COLUMN market.market_record.trade_direction IS
    'Price direction for grain-trade objects; OBSERVATION is reserved for agricultural-input-store own fields.';
