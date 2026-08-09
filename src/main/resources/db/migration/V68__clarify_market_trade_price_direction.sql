-- A market record represents one observed transaction-price side. Keep the
-- direction that selects the all-in price calculation, but distinguish it
-- clearly from the separate purchase/sales volume facts.
UPDATE platform.market_core_field_definition
SET label = '本次成交价格方向',
    description = '决定本条记录按采购基础价或销售基础价计算实际成交价'
WHERE code = 'MKT_TRADE_DIRECTION';

UPDATE platform.market_core_field_option
SET label = CASE value
    WHEN 'PURCHASE' THEN '采购成交'
    WHEN 'SALE' THEN '销售成交'
END
WHERE field_code = 'MKT_TRADE_DIRECTION';

UPDATE platform.field_definition
SET name = '市场本次成交价格方向'
WHERE code = 'MKT_TRADE_DIRECTION';

UPDATE platform.market_core_field_definition
SET description = CASE code
    WHEN 'MKT_PURCHASE_BASE_PRICE' THEN '选择采购成交时填写；不包含车板、包装和运费组成'
    WHEN 'MKT_SALE_BASE_PRICE' THEN '选择销售成交时填写；不包含车板、包装和运费组成'
END
WHERE code IN ('MKT_PURCHASE_BASE_PRICE', 'MKT_SALE_BASE_PRICE');

UPDATE platform.page_column_group_field
SET description = CASE field_code
    WHEN 'MKT_TRADE_DIRECTION' THEN '本条记录实际成交价采用的价格方向'
    WHEN 'MKT_PURCHASE_BASE_PRICE' THEN '选择采购成交时填写；不包含车板、包装和运费组成'
    WHEN 'MKT_SALE_BASE_PRICE' THEN '选择销售成交时填写；不包含车板、包装和运费组成'
END
WHERE business_domain = 'MARKET'
  AND page_kind = 'MONITORING'
  AND field_code IN ('MKT_TRADE_DIRECTION', 'MKT_PURCHASE_BASE_PRICE', 'MKT_SALE_BASE_PRICE');
