CREATE INDEX production_record_annual_comparison_approved_idx
    ON production.production_record(product_code, region_code, survey_date)
    INCLUDE (cultivar_code, cultivated_area_mu, estimated_output_kg, reported_at, version)
    WHERE status_code = 'APPROVED';

CREATE INDEX market_record_annual_comparison_approved_idx
    ON market.market_record(product_code, region_code, trade_date)
    INCLUDE (actual_trade_price, reported_at, version)
    WHERE status_code = 'APPROVED';

COMMENT ON INDEX production.production_record_annual_comparison_approved_idx IS
    'Four-year approved production overview comparison by product, authorized region and survey date.';
COMMENT ON INDEX market.market_record_annual_comparison_approved_idx IS
    'Four-year approved market overview comparison by product, authorized region and trade date.';
