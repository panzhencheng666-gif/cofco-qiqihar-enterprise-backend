ALTER TABLE production.production_record
    DROP CONSTRAINT production_record_status_code_check,
    ADD CONSTRAINT production_record_status_code_check
        CHECK (status_code IN ('DRAFT', 'PENDING_REVIEW', 'APPROVED', 'RETURNED', 'VOIDED'));

ALTER TABLE market.market_record
    DROP CONSTRAINT market_record_status_code_check,
    ADD CONSTRAINT market_record_status_code_check
        CHECK (status_code IN ('DRAFT', 'PENDING_REVIEW', 'APPROVED', 'RETURNED', 'VOIDED'));

ALTER TABLE logistics.route_event
    DROP CONSTRAINT route_event_status_code_check,
    ADD CONSTRAINT route_event_status_code_check
        CHECK (status_code IN ('DRAFT', 'PENDING_REVIEW', 'APPROVED', 'RETURNED', 'VOIDED'));

ALTER TABLE platform.logistics_action_applicability
    DROP CONSTRAINT logistics_action_applicability_status_code_check,
    ADD CONSTRAINT logistics_action_applicability_status_code_check
        CHECK (status_code IN ('DRAFT', 'PENDING_REVIEW', 'APPROVED', 'RETURNED', 'VOIDED'));

INSERT INTO platform.page_filter_option(
    product_code, business_domain, page_kind, filter_code, value, label, sort_order)
SELECT product.code, domain.code, 'MONITORING', 'status', 'VOIDED', '已作废', 50
FROM platform.product product
CROSS JOIN (VALUES ('PRODUCTION'), ('MARKET')) domain(code)
ON CONFLICT DO NOTHING;

INSERT INTO platform.logistics_core_field_option(field_code, value, label, sort_order)
VALUES ('LOG_STATUS', 'VOIDED', '已作废', 50)
ON CONFLICT DO NOTHING;

INSERT INTO platform.page_action(
    product_code, business_domain, page_kind, code, label, action_scope, sort_order)
SELECT product.code, domain.code, 'MONITORING', 'VOID', '作废', 'ROW', 60
FROM platform.product product
CROSS JOIN (VALUES ('PRODUCTION'), ('MARKET'), ('LOGISTICS')) domain(code)
ON CONFLICT DO NOTHING;

INSERT INTO platform.logistics_action_applicability(product_code, status_code, action_code)
SELECT product.code, policy.status_code, policy.action_code
FROM platform.product product
CROSS JOIN (VALUES
    ('DRAFT', 'VOID'),
    ('RETURNED', 'VOID'),
    ('VOIDED', 'VIEW')
) policy(status_code, action_code)
ON CONFLICT DO NOTHING;

COMMENT ON COLUMN production.production_record.status_code IS
    'DRAFT/RETURNED records may be voided; VOIDED is terminal and read-only.';
COMMENT ON COLUMN market.market_record.status_code IS
    'DRAFT/RETURNED records may be voided; VOIDED is terminal and read-only.';
COMMENT ON COLUMN logistics.route_event.status_code IS
    'DRAFT/RETURNED records may be voided; VOIDED is terminal and read-only.';
