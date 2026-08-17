-- Market draft creation reconciles the optional approved sample-point inventory
-- contract.  The contract remains governed by the migration owner; the normal
-- application runtime only needs read access for that reconciliation query.
GRANT SELECT ON TABLE market.sample_point_inventory_contract
TO qiqihar_enterprise_runtime;
