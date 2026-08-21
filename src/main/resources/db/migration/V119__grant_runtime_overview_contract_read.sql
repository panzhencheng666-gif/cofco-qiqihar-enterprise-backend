-- The runtime dashboard reads the active region-surplus calculation contract.
-- Keep this grant scoped to the contract table; runtime remains read-only.
GRANT SELECT ON TABLE overview.region_surplus_calculation_contract
TO qiqihar_enterprise_runtime;

-- The dashboard's region-surplus source query also reads governance state for
-- market inventory records. Keep that read scoped to the joined table.
GRANT SELECT ON TABLE market.market_inventory_governance
TO qiqihar_enterprise_runtime;
