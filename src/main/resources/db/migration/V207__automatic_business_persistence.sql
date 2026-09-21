-- Forward-only contract change; no historical business record is promoted here.
DELETE FROM platform.logistics_action_applicability WHERE action_code IN ('APPROVE','RETURN','SUBMIT');
DELETE FROM platform.page_action
WHERE business_domain IN ('PRODUCTION','MARKET','LOGISTICS') AND page_kind='MONITORING'
  AND code IN ('APPROVE','RETURN','SUBMIT');
INSERT INTO platform.logistics_action_applicability(product_code,status_code,action_code)
SELECT product.code,state.code,action.code FROM platform.product product
CROSS JOIN (VALUES ('DRAFT'),('PENDING_REVIEW'),('RETURNED'),('APPROVED')) state(code)
CROSS JOIN (VALUES ('VIEW'),('SAVE'),('VOID')) action(code)
ON CONFLICT DO NOTHING;
UPDATE platform.page_filter_option SET label=CASE value
 WHEN 'APPROVED' THEN '已入库' WHEN 'PENDING_REVIEW' THEN '待校验'
 WHEN 'RETURNED' THEN '待修正' WHEN 'DRAFT' THEN '待完善' ELSE label END
WHERE business_domain IN ('PRODUCTION','MARKET','LOGISTICS') AND filter_code='status';
UPDATE platform.logistics_core_field_option SET label=CASE value
 WHEN 'APPROVED' THEN '已入库' WHEN 'PENDING_REVIEW' THEN '待校验'
 WHEN 'RETURNED' THEN '待修正' WHEN 'DRAFT' THEN '待完善' ELSE label END
WHERE field_code='LOG_STATUS';
COMMENT ON COLUMN market.market_record.status_code IS
 'APPROVED means automatically validated formal data. Legacy pending/returned rows require business validation before saving; VOIDED is terminal.';
COMMENT ON COLUMN production.production_record.status_code IS
 'APPROVED means automatically validated formal data. Legacy pending/returned rows require business validation before saving; VOIDED is terminal.';
COMMENT ON COLUMN logistics.route_event.status_code IS
 'APPROVED means automatically validated formal data. Legacy pending/returned rows require business validation before saving; VOIDED is terminal.';
