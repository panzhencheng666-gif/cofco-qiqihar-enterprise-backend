-- Daily discovery must be able to register newly found public documents.
-- Keep existing read/update grants; do not grant delete or schema modification.
GRANT INSERT ON production.regional_public_source TO qiqihar_enterprise_runtime;
