UPDATE reporting.report_definition
SET active = false
WHERE business_domain IN ('SUBMISSION', 'COMPREHENSIVE');

COMMENT ON COLUMN reporting.report_definition.active IS
    'Only scoped production, market, logistics and supply report definitions are exposed to the formal business UI.';
