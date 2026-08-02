-- V17 is already applied and immutable. This forward migration completes the explicit
-- packaging price component and the database-owned market editor definition.
ALTER TABLE market.market_record
    ADD COLUMN packaging_amount numeric(18, 4) NOT NULL DEFAULT 0
        CHECK (packaging_amount >= 0);

ALTER TABLE market.market_record DROP COLUMN actual_trade_price;
ALTER TABLE market.market_record ADD COLUMN actual_trade_price numeric(18, 4)
    GENERATED ALWAYS AS (
        round((CASE WHEN trade_direction = 'PURCHASE' THEN purchase_base_price ELSE sale_base_price END)
            + carriage_board_amount + packaging_amount + freight_amount, 4)) STORED;

CREATE TABLE platform.market_core_field_definition (
    code varchar(60) PRIMARY KEY,
    label varchar(100) NOT NULL,
    control_type varchar(30) NOT NULL CHECK (control_type IN
        ('SELECT', 'REGION_HIERARCHY', 'DATE', 'DECIMAL', 'READONLY_DECIMAL')),
    unit varchar(40),
    decimal_precision integer CHECK (decimal_precision BETWEEN 1 AND 18),
    decimal_scale integer CHECK (decimal_scale BETWEEN 0 AND decimal_precision),
    sort_order integer NOT NULL UNIQUE,
    CHECK ((control_type IN ('DECIMAL', 'READONLY_DECIMAL')) = (decimal_precision IS NOT NULL))
);

CREATE TABLE platform.market_core_field_option (
    field_code varchar(60) NOT NULL REFERENCES platform.market_core_field_definition(code) ON DELETE CASCADE,
    value varchar(60) NOT NULL,
    label varchar(100) NOT NULL,
    sort_order integer NOT NULL,
    PRIMARY KEY (field_code, value),
    UNIQUE (field_code, sort_order)
);

INSERT INTO platform.market_core_field_definition
    (code, label, control_type, unit, decimal_precision, decimal_scale, sort_order)
VALUES
    ('MKT_OBJECT_TYPE', '对象类型', 'SELECT', NULL, NULL, NULL, 10),
    ('MKT_REGION', '地区', 'REGION_HIERARCHY', NULL, NULL, NULL, 20),
    ('MKT_TRADE_DATE', '交易日期', 'DATE', NULL, NULL, NULL, 30),
    ('MKT_TRADE_DIRECTION', '买卖方向', 'SELECT', NULL, NULL, NULL, 40),
    ('MKT_PURCHASE_BASE_PRICE', '采购基础价', 'DECIMAL', '元/吨', 18, 4, 50),
    ('MKT_SALE_BASE_PRICE', '销售基础价', 'DECIMAL', '元/吨', 18, 4, 60),
    ('MKT_CARRIAGE_BOARD_AMOUNT', '车板组成', 'DECIMAL', '元/吨', 18, 4, 70),
    ('MKT_PACKAGING_FORM', '包装形态', 'SELECT', NULL, NULL, NULL, 80),
    ('MKT_PACKAGING_AMOUNT', '包装组成', 'DECIMAL', '元/吨', 18, 4, 90),
    ('MKT_FREIGHT_AMOUNT', '运费组成', 'DECIMAL', '元/吨', 18, 4, 100),
    ('MKT_ACTUAL_TRADE_PRICE', '实际成交价', 'READONLY_DECIMAL', '元/吨', 18, 4, 110);

INSERT INTO platform.market_core_field_option(field_code, value, label, sort_order) VALUES
    ('MKT_TRADE_DIRECTION', 'PURCHASE', '采购', 10),
    ('MKT_TRADE_DIRECTION', 'SALE', '销售', 20),
    ('MKT_PACKAGING_FORM', 'BAGGED', '包粮', 10),
    ('MKT_PACKAGING_FORM', 'BULK', '散粮', 20);

INSERT INTO platform.field_definition(code, name, value_type) VALUES
    ('MKT_TRADE_DIRECTION', '市场买卖方向', 'TEXT'),
    ('MKT_CARRIAGE_BOARD_AMOUNT', '市场车板组成', 'DECIMAL'),
    ('MKT_PACKAGING_FORM', '市场包装形态', 'TEXT'),
    ('MKT_PACKAGING_AMOUNT', '市场包装组成', 'DECIMAL'),
    ('MKT_FREIGHT_AMOUNT', '市场运费组成', 'DECIMAL');

INSERT INTO platform.page_definition_field(product_code, business_domain, page_kind, field_code, sort_order)
SELECT product.code, 'MARKET', 'MONITORING', field.code, field.sort_order
FROM platform.product product CROSS JOIN (VALUES
    ('MKT_TRADE_DIRECTION', 35), ('MKT_CARRIAGE_BOARD_AMOUNT', 55),
    ('MKT_PACKAGING_FORM', 56), ('MKT_PACKAGING_AMOUNT', 57), ('MKT_FREIGHT_AMOUNT', 58)
) field(code, sort_order);

INSERT INTO platform.page_column_group_field
    (product_code, business_domain, page_kind, group_code, field_code, sort_order, unit)
SELECT product.code, 'MARKET', 'MONITORING', 'MARKET', field.code, field.sort_order, field.unit
FROM platform.product product CROSS JOIN (VALUES
    ('MKT_TRADE_DIRECTION', 35, NULL),
    ('MKT_CARRIAGE_BOARD_AMOUNT', 55, '元/吨'),
    ('MKT_PACKAGING_FORM', 56, NULL),
    ('MKT_PACKAGING_AMOUNT', 57, '元/吨'),
    ('MKT_FREIGHT_AMOUNT', 58, '元/吨')
) field(code, sort_order, unit);

COMMENT ON TABLE platform.market_core_field_definition IS
    'Database-owned market editor core fields; clients map stable field codes to draft properties.';
