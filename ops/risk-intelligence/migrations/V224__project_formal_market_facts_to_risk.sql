SET lock_timeout = '2s';
SET statement_timeout = '30s';

-- The risk runtime sees only the fields needed for a market fact. The view
-- owner reads the business table; the risk role receives no market schema grant.
CREATE VIEW risk.formal_market_fact_source AS
SELECT record.record_id,
       record.version,
       record.region_code,
       record.product_code,
       record.object_type_code,
       record.trade_date,
       record.reported_at,
       record.submitted_at,
       record.updated_at,
       record.status_code,
       record.trade_direction,
       record.purchase_base_price,
       record.sale_base_price,
       record.actual_trade_price
FROM market.market_record record
WHERE record.status_code IN ('APPROVED','VOIDED')
  AND record.survey_period_governance_state='CONFIRMED'
  AND record.submitted_at IS NOT NULL;

REVOKE ALL ON risk.formal_market_fact_source FROM PUBLIC;
GRANT SELECT ON risk.formal_market_fact_source TO qiqihar_risk_runtime;

COMMENT ON VIEW risk.formal_market_fact_source IS
    'Minimal read-only projection of submitted market facts for independent risk ingestion; contains no sample names or contacts.';
