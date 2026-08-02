-- V17 and V18 are installed and immutable. This forward migration exposes the
-- server-owned reporting time and makes price-component semantics visible to clients.
ALTER TABLE platform.market_core_field_definition
    ADD COLUMN description varchar(240);

ALTER TABLE platform.market_core_field_definition
    DROP CONSTRAINT market_core_field_definition_control_type_check;
ALTER TABLE platform.market_core_field_definition
    ADD CONSTRAINT market_core_field_definition_control_type_check
        CHECK (control_type IN
            ('SELECT', 'REGION_HIERARCHY', 'DATE', 'DECIMAL',
             'READONLY_DECIMAL', 'READONLY_DATETIME'));

INSERT INTO platform.market_core_field_definition
    (code, label, control_type, unit, decimal_precision, decimal_scale, sort_order, description)
VALUES ('MKT_REPORTED_AT', '填报时间', 'READONLY_DATETIME', NULL, NULL, NULL, 35, NULL);

UPDATE platform.market_core_field_definition
SET description = CASE code
    WHEN 'MKT_PURCHASE_BASE_PRICE' THEN '采购基础价未包含车板、包装和运费组成'
    WHEN 'MKT_SALE_BASE_PRICE' THEN '销售基础价未包含车板、包装和运费组成'
    WHEN 'MKT_ACTUAL_TRADE_PRICE' THEN '实际成交价已包含车板、包装和运费组成'
END
WHERE code IN ('MKT_PURCHASE_BASE_PRICE', 'MKT_SALE_BASE_PRICE', 'MKT_ACTUAL_TRADE_PRICE');

INSERT INTO platform.field_definition(code, name, value_type)
VALUES ('MKT_REPORTED_AT', '填报时间', 'DATETIME');

INSERT INTO platform.page_definition_field
    (product_code, business_domain, page_kind, field_code, sort_order)
SELECT code, 'MARKET', 'MONITORING', 'MKT_REPORTED_AT', 32
FROM platform.product;

INSERT INTO platform.page_column_group_field
    (product_code, business_domain, page_kind, group_code, field_code, sort_order, unit, description)
SELECT code, 'MARKET', 'MONITORING', 'MARKET', 'MKT_REPORTED_AT', 32, NULL, NULL
FROM platform.product;

UPDATE platform.page_column_group_field
SET description = CASE field_code
    WHEN 'MKT_PURCHASE_BASE_PRICE' THEN '采购基础价未包含车板、包装和运费组成'
    WHEN 'MKT_SALE_BASE_PRICE' THEN '销售基础价未包含车板、包装和运费组成'
    WHEN 'MKT_ACTUAL_TRADE_PRICE' THEN '实际成交价已包含车板、包装和运费组成'
END
WHERE business_domain = 'MARKET' AND page_kind = 'MONITORING'
  AND field_code IN ('MKT_PURCHASE_BASE_PRICE', 'MKT_SALE_BASE_PRICE', 'MKT_ACTUAL_TRADE_PRICE');
