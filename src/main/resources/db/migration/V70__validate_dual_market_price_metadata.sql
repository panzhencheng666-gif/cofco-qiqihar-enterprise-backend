-- V69 installs the replacement check as NOT VALID so its metadata rows can be
-- changed in the same transaction without PostgreSQL pending-trigger DDL
-- conflicts. A separate transaction validates the complete persisted graph.
ALTER TABLE platform.market_core_field_definition
    VALIDATE CONSTRAINT market_core_field_definition_supported_metadata_check;
