-- Market records own the lifecycle of their derived inventory-governance row.
-- Keep the approved sample-point inventory contract read-only, and grant the
-- runtime only the columns and delete operation used by reconciliation.
GRANT INSERT (record_id,status_code,reason_code,sample_point_id,resolved_by,resolved_at),
      UPDATE (status_code,reason_code,sample_point_id,resolved_by,resolved_at)
ON market.market_inventory_governance
TO qiqihar_enterprise_runtime;

GRANT DELETE ON TABLE market.market_inventory_governance
TO qiqihar_enterprise_runtime;
