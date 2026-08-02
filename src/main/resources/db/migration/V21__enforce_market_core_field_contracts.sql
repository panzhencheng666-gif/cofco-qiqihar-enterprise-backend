-- V17-V20 are installed and immutable. V21 makes market core-field ownership,
-- typed bindings, and supported UI metadata combinations database invariants.

ALTER TABLE platform.market_core_field_definition
    ADD CONSTRAINT market_core_field_definition_code_binding_unique
        UNIQUE (code, domain_binding),
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
            AND control_type = 'DECIMAL' AND NOT required)
        OR (domain_binding = 'SALE_BASE_PRICE' AND capability = 'SALE_BASE_PRICE'
            AND control_type = 'DECIMAL' AND NOT required)
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
    );

CREATE UNIQUE INDEX market_core_field_definition_typed_binding_unique
    ON platform.market_core_field_definition(domain_binding)
    WHERE domain_binding <> 'EXTENSION';

INSERT INTO platform.market_core_field_definition
    (code, label, control_type, unit, decimal_precision, decimal_scale, sort_order,
     description, domain_binding, capability, required)
VALUES
    ('MKT_CORN_SOURCE_NOTE', '玉米来源说明', 'TEXT', NULL, NULL, NULL, 106,
     '仅适用于玉米市场采集的数据库扩展字段', 'EXTENSION', 'GENERIC', false);

INSERT INTO platform.field_definition(code, name, value_type)
VALUES ('MKT_CORN_SOURCE_NOTE', '玉米市场来源说明', 'TEXT');

INSERT INTO platform.page_definition_field
    (product_code, business_domain, page_kind, field_code, sort_order)
VALUES ('CORN', 'MARKET', 'MONITORING', 'MKT_CORN_SOURCE_NOTE', 61);

INSERT INTO platform.page_column_group_field
    (product_code, business_domain, page_kind, group_code, field_code, sort_order,
     unit, description)
VALUES ('CORN', 'MARKET', 'MONITORING', 'MARKET', 'MKT_CORN_SOURCE_NOTE', 61,
        NULL, '仅适用于玉米市场采集的数据库扩展字段');

CREATE TABLE platform.market_core_field_applicability (
    product_code varchar(40) NOT NULL,
    business_domain varchar(30) NOT NULL DEFAULT 'MARKET'
        CHECK (business_domain = 'MARKET'),
    page_kind varchar(40) NOT NULL DEFAULT 'MONITORING'
        CHECK (page_kind = 'MONITORING'),
    field_code varchar(60) NOT NULL,
    domain_binding varchar(40) NOT NULL DEFAULT 'EXTENSION'
        CHECK (domain_binding = 'EXTENSION'),
    PRIMARY KEY (product_code, field_code),
    UNIQUE (product_code, field_code, domain_binding),
    FOREIGN KEY (product_code, business_domain, page_kind, field_code)
        REFERENCES platform.page_definition_field(
            product_code, business_domain, page_kind, field_code),
    FOREIGN KEY (field_code, domain_binding)
        REFERENCES platform.market_core_field_definition(code, domain_binding)
);

INSERT INTO platform.market_core_field_applicability
    (product_code, business_domain, page_kind, field_code, domain_binding)
SELECT page_field.product_code, page_field.business_domain, page_field.page_kind,
       definition.code, definition.domain_binding
FROM platform.page_definition_field page_field
JOIN platform.market_core_field_definition definition
  ON definition.code = page_field.field_code
WHERE page_field.business_domain = 'MARKET'
  AND page_field.page_kind = 'MONITORING'
  AND definition.domain_binding = 'EXTENSION';

ALTER TABLE market.market_record
    ADD CONSTRAINT market_record_id_product_unique UNIQUE (record_id, product_code);

ALTER TABLE market.market_record_core_value
    ADD COLUMN product_code varchar(40),
    ADD COLUMN domain_binding varchar(40);

UPDATE market.market_record_core_value value
SET product_code = record.product_code,
    domain_binding = definition.domain_binding
FROM market.market_record record,
     platform.market_core_field_definition definition
WHERE record.record_id = value.record_id
  AND definition.code = value.field_code;

ALTER TABLE market.market_record_core_value
    ALTER COLUMN product_code SET NOT NULL,
    ALTER COLUMN domain_binding SET NOT NULL,
    ADD CONSTRAINT market_record_core_value_extension_binding_check
        CHECK (domain_binding = 'EXTENSION'),
    ADD CONSTRAINT market_record_core_value_record_product_fk
        FOREIGN KEY (record_id, product_code)
        REFERENCES market.market_record(record_id, product_code) ON DELETE CASCADE,
    ADD CONSTRAINT market_record_core_value_applicability_fk
        FOREIGN KEY (product_code, field_code, domain_binding)
        REFERENCES platform.market_core_field_applicability(
            product_code, field_code, domain_binding);

COMMENT ON TABLE platform.market_core_field_applicability IS
    'Product-specific MARKET/MONITORING extension fields mounted by page definition.';
COMMENT ON COLUMN market.market_record_core_value.product_code IS
    'Owner product, constrained to the record and mounted extension field applicability.';
