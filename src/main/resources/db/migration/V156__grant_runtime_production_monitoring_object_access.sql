-- V155 created the governed production monitoring-object aggregate after the
-- V111 runtime-role snapshot. Grant only the operations used by
-- JdbcProductionObjectRepository; revisions remain append-only and the current
-- object has no delete contract.
GRANT SELECT ON TABLE
    production.production_object_type_definition,
    production.production_source_channel_definition,
    production.production_business_role_definition
TO qiqihar_enterprise_runtime;

GRANT SELECT,INSERT,UPDATE ON TABLE production.monitoring_object
TO qiqihar_enterprise_runtime;

GRANT SELECT,INSERT,DELETE ON TABLE
    production.monitoring_object_product,
    production.monitoring_object_cultivar,
    production.monitoring_object_role_assignment
TO qiqihar_enterprise_runtime;

GRANT SELECT,INSERT ON TABLE production.monitoring_object_revision
TO qiqihar_enterprise_runtime;
