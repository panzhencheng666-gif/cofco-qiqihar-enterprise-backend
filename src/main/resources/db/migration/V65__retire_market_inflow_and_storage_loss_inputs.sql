-- These two survey inputs were retired from active market submissions.  Historical
-- facts remain readable; removing applicability prevents new forms/imports from accepting them.
DELETE FROM platform.market_fact_applicability
WHERE fact_code IN ('STOCK_INFLOW', 'STORAGE_LOSS');
