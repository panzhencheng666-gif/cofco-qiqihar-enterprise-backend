-- A market record describes the surveyed object, not a transaction performed
-- by the platform operator. New records therefore capture the object's
-- purchase and sale prices together. PURCHASE/SALE remain readable for
-- historical records; BOTH is the internal provenance marker for the new
-- dual-price model and is deliberately not exposed as a form field.
ALTER TABLE market.market_record
    DROP COLUMN actual_trade_price,
    DROP CONSTRAINT market_record_trade_direction_check,
    DROP CONSTRAINT market_record_check1;

ALTER TABLE market.market_record
    ADD CONSTRAINT market_record_trade_direction_check
        CHECK (trade_direction IN ('PURCHASE', 'SALE', 'BOTH')),
    ADD CONSTRAINT market_record_price_model_check CHECK (
        (trade_direction = 'PURCHASE' AND purchase_base_price IS NOT NULL)
        OR (trade_direction = 'SALE' AND sale_base_price IS NOT NULL)
        OR (trade_direction = 'BOTH'
            AND purchase_base_price IS NOT NULL
            AND sale_base_price IS NOT NULL)
    ),
    ADD COLUMN actual_trade_price numeric(18, 4)
        GENERATED ALWAYS AS (
            round((
                CASE
                    WHEN trade_direction = 'PURCHASE' THEN purchase_base_price
                    WHEN trade_direction = 'SALE' THEN sale_base_price
                    ELSE (purchase_base_price + sale_base_price) / 2
                END
            ) + carriage_board_amount + packaging_amount + freight_amount, 4)
        ) STORED;

ALTER TABLE platform.market_core_field_definition
    DROP CONSTRAINT market_core_field_definition_supported_metadata_check;

ALTER TABLE platform.market_core_field_definition
    ADD CONSTRAINT market_core_field_definition_supported_metadata_check CHECK (
        (domain_binding = 'OBJECT_TYPE' AND capability = 'OBJECT_TYPE_CONTEXT'
            AND control_type = 'SELECT' AND required)
        OR (domain_binding = 'REGION' AND capability = 'GENERIC'
            AND control_type = 'REGION_HIERARCHY' AND required)
        OR (domain_binding = 'TRADE_DATE' AND capability = 'GENERIC'
            AND control_type = 'DATE' AND required)
        OR (domain_binding = 'REPORTED_AT' AND capability = 'GENERIC'
            AND control_type = 'READONLY_DATETIME' AND NOT required)
        OR (domain_binding = 'TRADE_DIRECTION' AND capability = 'PRICE_DIRECTION'
            AND control_type = 'SELECT' AND required)
        OR (domain_binding = 'PURCHASE_BASE_PRICE' AND capability = 'PURCHASE_BASE_PRICE'
            AND control_type = 'DECIMAL' AND required)
        OR (domain_binding = 'SALE_BASE_PRICE' AND capability = 'SALE_BASE_PRICE'
            AND control_type = 'DECIMAL' AND required)
        OR (domain_binding = 'CARRIAGE_BOARD_AMOUNT' AND capability = 'PRICE_COMPONENT'
            AND control_type = 'DECIMAL' AND required)
        OR (domain_binding = 'PACKAGING_FORM' AND capability = 'GENERIC'
            AND control_type = 'SELECT' AND required)
        OR (domain_binding = 'PACKAGING_AMOUNT' AND capability = 'PRICE_COMPONENT'
            AND control_type = 'DECIMAL' AND required)
        OR (domain_binding = 'FREIGHT_AMOUNT' AND capability = 'PRICE_COMPONENT'
            AND control_type = 'DECIMAL' AND required)
        OR (domain_binding = 'ACTUAL_TRADE_PRICE' AND capability = 'ACTUAL_TRADE_PRICE'
            AND control_type = 'READONLY_DECIMAL' AND NOT required)
        OR (domain_binding = 'EXTENSION' AND capability = 'GENERIC'
            AND control_type IN ('TEXT', 'DECIMAL'))
    ) NOT VALID;

UPDATE platform.market_core_field_definition
SET label = CASE code
        WHEN 'MKT_PURCHASE_BASE_PRICE' THEN '对象采购价格'
        WHEN 'MKT_SALE_BASE_PRICE' THEN '对象销售价格'
    END,
    description = CASE code
        WHEN 'MKT_PURCHASE_BASE_PRICE' THEN '被调查对象当前对外采购报价'
        WHEN 'MKT_SALE_BASE_PRICE' THEN '被调查对象当前对外销售报价'
    END,
    required = true
WHERE code IN ('MKT_PURCHASE_BASE_PRICE', 'MKT_SALE_BASE_PRICE');

UPDATE platform.field_definition
SET name = CASE code
        WHEN 'MKT_PURCHASE_BASE_PRICE' THEN '市场对象采购价格'
        WHEN 'MKT_SALE_BASE_PRICE' THEN '市场对象销售价格'
    END
WHERE code IN ('MKT_PURCHASE_BASE_PRICE', 'MKT_SALE_BASE_PRICE');

UPDATE platform.page_column_group_field
SET description = CASE field_code
        WHEN 'MKT_PURCHASE_BASE_PRICE' THEN '被调查对象当前对外采购报价'
        WHEN 'MKT_SALE_BASE_PRICE' THEN '被调查对象当前对外销售报价'
    END
WHERE business_domain = 'MARKET'
  AND page_kind = 'MONITORING'
  AND field_code IN ('MKT_PURCHASE_BASE_PRICE', 'MKT_SALE_BASE_PRICE');

DELETE FROM platform.page_column_group_field
WHERE business_domain = 'MARKET'
  AND page_kind = 'MONITORING'
  AND field_code IN ('MKT_TRADE_DIRECTION', 'MKT_ACTUAL_TRADE_PRICE');

DELETE FROM platform.page_definition_field
WHERE business_domain = 'MARKET'
  AND page_kind = 'MONITORING'
  AND field_code IN ('MKT_TRADE_DIRECTION', 'MKT_ACTUAL_TRADE_PRICE');

DELETE FROM platform.market_core_field_applicability
WHERE business_domain = 'MARKET'
  AND page_kind = 'MONITORING'
  AND field_code IN ('MKT_TRADE_DIRECTION', 'MKT_ACTUAL_TRADE_PRICE');

DELETE FROM platform.market_core_typed_binding_requirement
WHERE domain_binding IN ('TRADE_DIRECTION', 'ACTUAL_TRADE_PRICE');

DELETE FROM platform.market_core_field_definition
WHERE code IN ('MKT_TRADE_DIRECTION', 'MKT_ACTUAL_TRADE_PRICE');

DELETE FROM platform.field_definition
WHERE code IN ('MKT_TRADE_DIRECTION', 'MKT_ACTUAL_TRADE_PRICE');

COMMENT ON COLUMN market.market_record.trade_direction IS
    'Legacy PURCHASE/SALE provenance or BOTH for the active dual-price survey model; not user-editable.';
COMMENT ON COLUMN market.market_record.actual_trade_price IS
    'Backward-compatible indicative midpoint for existing aggregate readers; not an active form or ledger field.';
