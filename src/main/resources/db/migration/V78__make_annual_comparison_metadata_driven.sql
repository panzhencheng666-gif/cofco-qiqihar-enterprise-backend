ALTER TABLE overview.indicator_definition
    ADD COLUMN aggregation_code varchar(16) NOT NULL DEFAULT 'SUM',
    ADD COLUMN annual_comparison_enabled boolean NOT NULL DEFAULT false,
    ADD CONSTRAINT indicator_definition_aggregation_check
        CHECK (aggregation_code IN ('SUM', 'AVERAGE'));

UPDATE overview.indicator_definition
SET annual_comparison_enabled = true,
    aggregation_code = CASE
        WHEN code = 'MARKET_AVERAGE_TRADE_PRICE' THEN 'AVERAGE'
        ELSE 'SUM'
    END
WHERE code IN (
    'PRODUCTION_CULTIVATED_AREA',
    'PRODUCTION_ESTIMATED_OUTPUT',
    'MARKET_AVERAGE_TRADE_PRICE'
);

CREATE TABLE overview.annual_comparison_metric_binding (
    metric_code varchar(80) PRIMARY KEY
        REFERENCES overview.indicator_definition(code) ON DELETE CASCADE,
    storage_code varchar(40) NOT NULL CHECK (storage_code IN (
        'PRODUCTION_CORE',
        'PRODUCTION_METADATA',
        'PRODUCTION_QUALITY',
        'PRODUCTION_COST',
        'PRODUCTION_INSURANCE',
        'PRODUCTION_SUBSIDY',
        'MARKET_CORE',
        'MARKET_FACT'
    )),
    field_code varchar(60) NOT NULL,
    UNIQUE (storage_code, field_code)
);

INSERT INTO overview.annual_comparison_metric_binding(metric_code,storage_code,field_code) VALUES
    ('PRODUCTION_CULTIVATED_AREA','PRODUCTION_CORE','cultivated_area_mu'),
    ('PRODUCTION_ESTIMATED_OUTPUT','PRODUCTION_CORE','estimated_output_kg'),
    ('MARKET_AVERAGE_TRADE_PRICE','MARKET_CORE','actual_trade_price');

INSERT INTO overview.indicator_definition(
    code,name,unit_code,source_domain,sort_order,aggregation_code,annual_comparison_enabled
) VALUES
    ('PRODUCTION_AVERAGE_YIELD_PER_MU','核定预计单产','公斤/亩','PRODUCTION',110,'AVERAGE',true),
    ('MARKET_AVERAGE_PURCHASE_PRICE','核定平均采购价格','元/吨','MARKET',120,'AVERAGE',true),
    ('MARKET_AVERAGE_SALE_PRICE','核定平均销售价格','元/吨','MARKET',130,'AVERAGE',true),
    ('MARKET_AVERAGE_CARRIAGE_BOARD_AMOUNT','核定平均车板组成','元/吨','MARKET',140,'AVERAGE',true),
    ('MARKET_AVERAGE_PACKAGING_AMOUNT','核定平均包装组成','元/吨','MARKET',150,'AVERAGE',true),
    ('MARKET_AVERAGE_FREIGHT_AMOUNT','核定平均运费组成','元/吨','MARKET',160,'AVERAGE',true);

INSERT INTO overview.annual_comparison_metric_binding(metric_code,storage_code,field_code) VALUES
    ('PRODUCTION_AVERAGE_YIELD_PER_MU','PRODUCTION_CORE','yield_per_mu_kg'),
    ('MARKET_AVERAGE_PURCHASE_PRICE','MARKET_CORE','purchase_base_price'),
    ('MARKET_AVERAGE_SALE_PRICE','MARKET_CORE','sale_base_price'),
    ('MARKET_AVERAGE_CARRIAGE_BOARD_AMOUNT','MARKET_CORE','carriage_board_amount'),
    ('MARKET_AVERAGE_PACKAGING_AMOUNT','MARKET_CORE','packaging_amount'),
    ('MARKET_AVERAGE_FREIGHT_AMOUNT','MARKET_CORE','freight_amount');

WITH numeric_definition AS (
    SELECT definition.*,
           row_number() OVER (ORDER BY applicability.sort_order,definition.code) sequence
    FROM platform.production_fact_definition definition
    JOIN (
        SELECT fact_code,MIN(sort_order) sort_order
        FROM platform.production_fact_applicability
        GROUP BY fact_code
    ) applicability ON applicability.fact_code=definition.code
    WHERE definition.value_type='DECIMAL'
)
INSERT INTO overview.indicator_definition(
    code,name,unit_code,source_domain,sort_order,aggregation_code,annual_comparison_enabled
)
SELECT 'PRODUCTION_'||code,'产情核定'||label,COALESCE(unit,''),'PRODUCTION',
       1000+sequence,
       CASE WHEN category IN ('QUALITY','COST') THEN 'AVERAGE' ELSE 'SUM' END,
       true
FROM numeric_definition;

INSERT INTO overview.annual_comparison_metric_binding(metric_code,storage_code,field_code)
SELECT 'PRODUCTION_'||code,
       CASE category
           WHEN 'DETAIL' THEN 'PRODUCTION_METADATA'
           WHEN 'QUALITY' THEN 'PRODUCTION_QUALITY'
           WHEN 'COST' THEN 'PRODUCTION_COST'
           WHEN 'INSURANCE' THEN 'PRODUCTION_INSURANCE'
           WHEN 'SUBSIDY' THEN 'PRODUCTION_SUBSIDY'
       END,
       code
FROM platform.production_fact_definition definition
WHERE definition.value_type='DECIMAL'
  AND EXISTS (
      SELECT 1 FROM platform.production_fact_applicability applicability
      WHERE applicability.fact_code=definition.code
  );

WITH applicable_definition AS (
    SELECT definition.*,
           row_number() OVER (ORDER BY category,code) sequence
    FROM platform.market_fact_definition definition
    WHERE EXISTS (
        SELECT 1 FROM platform.market_fact_applicability applicability
        WHERE applicability.fact_code=definition.code
    )
)
INSERT INTO overview.indicator_definition(
    code,name,unit_code,source_domain,sort_order,aggregation_code,annual_comparison_enabled
)
SELECT 'MARKET_'||code,'市场核定'||label,COALESCE(unit,''),'MARKET',
       2000+sequence,
       CASE WHEN category='QUALITY' THEN 'AVERAGE' ELSE 'SUM' END,
       true
FROM applicable_definition;

INSERT INTO overview.annual_comparison_metric_binding(metric_code,storage_code,field_code)
SELECT 'MARKET_'||definition.code,'MARKET_FACT',definition.code
FROM platform.market_fact_definition definition
WHERE EXISTS (
    SELECT 1 FROM platform.market_fact_applicability applicability
    WHERE applicability.fact_code=definition.code
);

CREATE VIEW overview.approved_annual_metric_fact AS
SELECT binding.metric_code,record.product_code,record.cultivar_code,record.region_code,
       record.survey_date occurred_on,record.record_id,record.version,record.reported_at,
       (to_jsonb(record)->>binding.field_code)::numeric value
FROM production.production_record record
JOIN overview.annual_comparison_metric_binding binding
  ON binding.storage_code='PRODUCTION_CORE'
WHERE record.status_code='APPROVED'
  AND to_jsonb(record)->>binding.field_code IS NOT NULL
UNION ALL
SELECT binding.metric_code,record.product_code,record.cultivar_code,record.region_code,
       record.survey_date,record.record_id,record.version,record.reported_at,metadata.value::numeric
FROM production.production_record record
JOIN production.production_record_submission_metadata metadata ON metadata.record_id=record.record_id
JOIN overview.annual_comparison_metric_binding binding
  ON binding.storage_code='PRODUCTION_METADATA' AND binding.field_code=metadata.field_code
WHERE record.status_code='APPROVED'
  AND metadata.value ~ '^[+-]?[0-9]+([.][0-9]+)?$'
UNION ALL
SELECT binding.metric_code,record.product_code,record.cultivar_code,record.region_code,
       record.survey_date,record.record_id,record.version,record.reported_at,fact.value
FROM production.production_record record
JOIN production.production_record_quality fact ON fact.record_id=record.record_id
JOIN overview.annual_comparison_metric_binding binding
  ON binding.storage_code='PRODUCTION_QUALITY' AND binding.field_code=fact.quality_code
WHERE record.status_code='APPROVED'
UNION ALL
SELECT binding.metric_code,record.product_code,record.cultivar_code,record.region_code,
       record.survey_date,record.record_id,record.version,record.reported_at,fact.value
FROM production.production_record record
JOIN production.production_record_cost fact ON fact.record_id=record.record_id
JOIN overview.annual_comparison_metric_binding binding
  ON binding.storage_code='PRODUCTION_COST' AND binding.field_code=fact.cost_code
WHERE record.status_code='APPROVED'
UNION ALL
SELECT binding.metric_code,record.product_code,record.cultivar_code,record.region_code,
       record.survey_date,record.record_id,record.version,record.reported_at,fact.value
FROM production.production_record record
JOIN production.production_record_insurance fact ON fact.record_id=record.record_id
JOIN overview.annual_comparison_metric_binding binding
  ON binding.storage_code='PRODUCTION_INSURANCE' AND binding.field_code=fact.insurance_code
WHERE record.status_code='APPROVED'
UNION ALL
SELECT binding.metric_code,record.product_code,record.cultivar_code,record.region_code,
       record.survey_date,record.record_id,record.version,record.reported_at,fact.value
FROM production.production_record record
JOIN production.production_record_subsidy fact ON fact.record_id=record.record_id
JOIN overview.annual_comparison_metric_binding binding
  ON binding.storage_code='PRODUCTION_SUBSIDY' AND binding.field_code=fact.subsidy_code
WHERE record.status_code='APPROVED'
UNION ALL
SELECT binding.metric_code,record.product_code,NULL::varchar cultivar_code,record.region_code,
       record.trade_date,record.record_id,record.version,record.reported_at,
       (to_jsonb(record)->>binding.field_code)::numeric value
FROM market.market_record record
JOIN overview.annual_comparison_metric_binding binding
  ON binding.storage_code='MARKET_CORE'
WHERE record.status_code='APPROVED'
  AND to_jsonb(record)->>binding.field_code IS NOT NULL
UNION ALL
SELECT binding.metric_code,record.product_code,NULL::varchar cultivar_code,record.region_code,
       record.trade_date,record.record_id,record.version,record.reported_at,fact.value
FROM market.market_record record
JOIN market.market_record_fact fact ON fact.record_id=record.record_id
JOIN overview.annual_comparison_metric_binding binding
  ON binding.storage_code='MARKET_FACT' AND binding.field_code=fact.fact_code
WHERE record.status_code='APPROVED';

COMMENT ON TABLE overview.annual_comparison_metric_binding IS
    'Database-owned mapping from analysis indicators to approved form/list facts; the UI never owns a static indicator list.';
COMMENT ON VIEW overview.approved_annual_metric_fact IS
    'Normalized approved production and market facts used by the generic four-year comparison engine.';
