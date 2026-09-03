ALTER TABLE production.production_record
    ALTER COLUMN cultivated_area_mu DROP NOT NULL,
    ALTER COLUMN yield_per_mu_kg DROP NOT NULL;

ALTER TABLE market.market_record
    DROP COLUMN actual_trade_price,
    DROP CONSTRAINT market_record_price_model_check;

ALTER TABLE market.market_record
    ADD CONSTRAINT market_record_price_model_check CHECK (
        (trade_direction = 'PURCHASE' AND purchase_base_price IS NOT NULL)
        OR (trade_direction = 'SALE' AND sale_base_price IS NOT NULL)
        OR (trade_direction = 'BOTH'
            AND purchase_base_price IS NOT NULL AND sale_base_price IS NOT NULL)
        OR (trade_direction = 'OBSERVATION'
            AND purchase_base_price IS NULL AND sale_base_price IS NULL
            AND carriage_board_amount IS NULL AND packaging_amount IS NULL
            AND freight_amount IS NULL AND packaging_form IS NULL)
    ),
    ADD COLUMN actual_trade_price numeric(18, 4) GENERATED ALWAYS AS (
        CASE WHEN trade_direction = 'OBSERVATION' THEN NULL ELSE
            round((CASE
                WHEN trade_direction = 'PURCHASE' THEN purchase_base_price
                WHEN trade_direction = 'SALE' THEN sale_base_price
                ELSE (purchase_base_price + sale_base_price) / 2
            END) + coalesce(carriage_board_amount, 0)
                 + coalesce(packaging_amount, 0)
                 + coalesce(freight_amount, 0), 4)
        END
    ) STORED;

ALTER TABLE platform.market_core_field_definition
    DROP CONSTRAINT market_core_field_definition_supported_metadata_check;

UPDATE platform.market_core_field_definition
SET required = false
WHERE domain_binding IN (
    'PURCHASE_BASE_PRICE', 'SALE_BASE_PRICE', 'CARRIAGE_BOARD_AMOUNT',
    'PACKAGING_FORM', 'PACKAGING_AMOUNT', 'FREIGHT_AMOUNT'
);

SET CONSTRAINTS ALL IMMEDIATE;

ALTER TABLE platform.market_core_field_definition
    ADD CONSTRAINT market_core_field_definition_supported_metadata_check CHECK (
        (domain_binding='OBJECT_TYPE' AND capability='OBJECT_TYPE_CONTEXT'
            AND control_type='SELECT' AND required)
        OR (domain_binding='REGION' AND capability='GENERIC'
            AND control_type='REGION_HIERARCHY' AND required)
        OR (domain_binding='TRADE_DATE' AND capability='GENERIC'
            AND control_type='DATE' AND required)
        OR (domain_binding='REPORTED_AT' AND capability='GENERIC'
            AND control_type='READONLY_DATETIME' AND NOT required)
        OR (domain_binding='PURCHASE_BASE_PRICE' AND capability='PURCHASE_BASE_PRICE'
            AND control_type='DECIMAL' AND NOT required)
        OR (domain_binding='SALE_BASE_PRICE' AND capability='SALE_BASE_PRICE'
            AND control_type='DECIMAL' AND NOT required)
        OR (domain_binding='CARRIAGE_BOARD_AMOUNT' AND capability='PRICE_COMPONENT'
            AND control_type='DECIMAL' AND NOT required)
        OR (domain_binding='PACKAGING_FORM' AND capability='GENERIC'
            AND control_type='SELECT' AND NOT required)
        OR (domain_binding='PACKAGING_AMOUNT' AND capability='PRICE_COMPONENT'
            AND control_type='DECIMAL' AND NOT required)
        OR (domain_binding='FREIGHT_AMOUNT' AND capability='PRICE_COMPONENT'
            AND control_type='DECIMAL' AND NOT required)
        OR (domain_binding='EXTENSION' AND capability='GENERIC'
            AND control_type IN ('TEXT','DECIMAL','SELECT','REGION_HIERARCHY','DATE'))
    );

COMMENT ON COLUMN production.production_record.cultivated_area_mu IS
    'Optional reported business value; null means not supplied.';
COMMENT ON COLUMN production.production_record.yield_per_mu_kg IS
    'Optional reported business value; null means not supplied.';
