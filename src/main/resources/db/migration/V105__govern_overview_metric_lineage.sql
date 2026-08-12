ALTER TABLE overview.indicator_definition
    ADD COLUMN formula text,
    ADD COLUMN source_relation varchar(160),
    ADD COLUMN calculation_version varchar(80) NOT NULL DEFAULT 'OVERVIEW_METRIC_V1';

UPDATE overview.indicator_definition
SET formula = CASE code
        WHEN 'PRODUCTION_CULTIVATED_AREA' THEN '核定种植面积合计'
        WHEN 'PRODUCTION_ESTIMATED_OUTPUT' THEN '核定预计产量合计'
        WHEN 'MARKET_AVERAGE_TRADE_PRICE' THEN '核定成交价格算术平均值'
        WHEN 'LOGISTICS_INFLOW_VOLUME' THEN '进入所选范围的核定物流数量折合吨后合计'
        WHEN 'LOGISTICS_OUTFLOW_VOLUME' THEN '离开所选范围的核定物流数量折合吨后合计'
        WHEN 'SUPPLY_TOTAL_SUPPLY' THEN '最新已发布且期次确认的供给总量合计'
        WHEN 'SUPPLY_TOTAL_USE' THEN '最新已发布且期次确认的使用总量合计'
        WHEN 'SUPPLY_ADOPTED_ENDING_INVENTORY' THEN '最新已发布且期次确认的采用期末库存合计'
        WHEN 'REGION_SURPLUS' THEN '互斥归属核验后的正式库存合计'
        ELSE '核定业务事实按指标定义汇总'
    END,
    source_relation = CASE source_domain
        WHEN 'PRODUCTION' THEN '产情核定记录'
        WHEN 'MARKET' THEN '市场核定记录'
        WHEN 'LOGISTICS' THEN '物流核定事件及数量明细'
        WHEN 'SUPPLY' THEN '供需正式计算结果'
    END,
    calculation_version = '总揽指标口径第1版';

ALTER TABLE overview.indicator_definition
    ALTER COLUMN formula SET NOT NULL,
    ALTER COLUMN source_relation SET NOT NULL;

COMMENT ON COLUMN overview.indicator_definition.formula IS
    'Database-owned aggregation semantics; API values may only be calculated from approved/formal, period-governed source rows.';
COMMENT ON COLUMN overview.indicator_definition.source_relation IS
    'Canonical source lineage. It is a database relation boundary, not a browser-supplied label.';
COMMENT ON COLUMN overview.indicator_definition.calculation_version IS
    'Stable calculation contract version emitted with every indicator and dashboard metric.';
