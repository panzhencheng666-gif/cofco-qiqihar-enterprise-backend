-- V17-V19 are installed and immutable. V20 makes the market editor transport
-- code-keyed while retaining the typed aggregate and normalized database model.
ALTER TABLE platform.market_core_field_definition
    DROP CONSTRAINT market_core_field_definition_control_type_check;
ALTER TABLE platform.market_core_field_definition
    ADD CONSTRAINT market_core_field_definition_control_type_check
        CHECK (control_type IN
            ('SELECT', 'REGION_HIERARCHY', 'DATE', 'DECIMAL', 'TEXT',
             'READONLY_DECIMAL', 'READONLY_DATETIME'));

ALTER TABLE platform.market_core_field_definition
    ADD COLUMN domain_binding varchar(40),
    ADD COLUMN capability varchar(40),
    ADD COLUMN required boolean NOT NULL DEFAULT false;

UPDATE platform.market_core_field_definition
SET domain_binding = CASE code
        WHEN 'MKT_OBJECT_TYPE' THEN 'OBJECT_TYPE'
        WHEN 'MKT_REGION' THEN 'REGION'
        WHEN 'MKT_TRADE_DATE' THEN 'TRADE_DATE'
        WHEN 'MKT_REPORTED_AT' THEN 'REPORTED_AT'
        WHEN 'MKT_TRADE_DIRECTION' THEN 'TRADE_DIRECTION'
        WHEN 'MKT_PURCHASE_BASE_PRICE' THEN 'PURCHASE_BASE_PRICE'
        WHEN 'MKT_SALE_BASE_PRICE' THEN 'SALE_BASE_PRICE'
        WHEN 'MKT_CARRIAGE_BOARD_AMOUNT' THEN 'CARRIAGE_BOARD_AMOUNT'
        WHEN 'MKT_PACKAGING_FORM' THEN 'PACKAGING_FORM'
        WHEN 'MKT_PACKAGING_AMOUNT' THEN 'PACKAGING_AMOUNT'
        WHEN 'MKT_FREIGHT_AMOUNT' THEN 'FREIGHT_AMOUNT'
        WHEN 'MKT_ACTUAL_TRADE_PRICE' THEN 'ACTUAL_TRADE_PRICE'
    END,
    capability = CASE code
        WHEN 'MKT_OBJECT_TYPE' THEN 'OBJECT_TYPE_CONTEXT'
        WHEN 'MKT_TRADE_DIRECTION' THEN 'PRICE_DIRECTION'
        WHEN 'MKT_PURCHASE_BASE_PRICE' THEN 'PURCHASE_BASE_PRICE'
        WHEN 'MKT_SALE_BASE_PRICE' THEN 'SALE_BASE_PRICE'
        WHEN 'MKT_CARRIAGE_BOARD_AMOUNT' THEN 'PRICE_COMPONENT'
        WHEN 'MKT_PACKAGING_AMOUNT' THEN 'PRICE_COMPONENT'
        WHEN 'MKT_FREIGHT_AMOUNT' THEN 'PRICE_COMPONENT'
        WHEN 'MKT_ACTUAL_TRADE_PRICE' THEN 'ACTUAL_TRADE_PRICE'
        ELSE 'GENERIC'
    END,
    required = code IN (
        'MKT_OBJECT_TYPE', 'MKT_REGION', 'MKT_TRADE_DATE', 'MKT_TRADE_DIRECTION',
        'MKT_CARRIAGE_BOARD_AMOUNT', 'MKT_PACKAGING_FORM',
        'MKT_PACKAGING_AMOUNT', 'MKT_FREIGHT_AMOUNT');

ALTER TABLE platform.market_core_field_definition
    ALTER COLUMN domain_binding SET NOT NULL,
    ALTER COLUMN capability SET NOT NULL;

ALTER TABLE platform.market_core_field_definition
    ADD CONSTRAINT market_core_field_definition_binding_check CHECK (domain_binding IN
        ('OBJECT_TYPE', 'REGION', 'TRADE_DATE', 'REPORTED_AT', 'TRADE_DIRECTION',
         'PURCHASE_BASE_PRICE', 'SALE_BASE_PRICE', 'CARRIAGE_BOARD_AMOUNT',
         'PACKAGING_FORM', 'PACKAGING_AMOUNT', 'FREIGHT_AMOUNT',
         'ACTUAL_TRADE_PRICE', 'EXTENSION')),
    ADD CONSTRAINT market_core_field_definition_capability_check CHECK (capability IN
        ('GENERIC', 'OBJECT_TYPE_CONTEXT', 'PRICE_DIRECTION', 'PURCHASE_BASE_PRICE',
         'SALE_BASE_PRICE', 'PRICE_COMPONENT', 'ACTUAL_TRADE_PRICE'));

INSERT INTO platform.market_core_field_definition
    (code, label, control_type, unit, decimal_precision, decimal_scale, sort_order,
     description, domain_binding, capability, required)
VALUES
    ('MKT_SOURCE_NOTE', '来源说明', 'TEXT', NULL, NULL, NULL, 105,
     '由数据库定义的可扩展核心字段', 'EXTENSION', 'GENERIC', false);

CREATE TABLE market.market_record_core_value (
    record_id varchar(36) NOT NULL
        REFERENCES market.market_record(record_id) ON DELETE CASCADE,
    field_code varchar(60) NOT NULL
        REFERENCES platform.market_core_field_definition(code),
    value varchar(500) NOT NULL CHECK (btrim(value) <> ''),
    PRIMARY KEY (record_id, field_code)
);

INSERT INTO platform.field_definition(code, name, value_type)
VALUES ('MKT_SOURCE_NOTE', '市场来源说明', 'TEXT');

INSERT INTO platform.page_definition_field
    (product_code, business_domain, page_kind, field_code, sort_order)
SELECT code, 'MARKET', 'MONITORING', 'MKT_SOURCE_NOTE', 59
FROM platform.product;

INSERT INTO platform.page_column_group_field
    (product_code, business_domain, page_kind, group_code, field_code, sort_order, unit, description)
SELECT code, 'MARKET', 'MONITORING', 'MARKET', 'MKT_SOURCE_NOTE', 59, NULL,
       '由数据库定义的可扩展核心字段'
FROM platform.product;

COMMENT ON TABLE market.market_record_core_value IS
    'Normalized values for database-defined market core fields without typed aggregate columns.';
