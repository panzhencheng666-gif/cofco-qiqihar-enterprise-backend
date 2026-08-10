DROP INDEX market.monitoring_object_one_dossier_per_subject_name;

CREATE UNIQUE INDEX monitoring_object_duplicate_name_guard
    ON market.monitoring_object(region_code, lower(btrim(object_name)));

COMMENT ON INDEX market.monitoring_object_duplicate_name_guard IS
    'Convenience duplicate-name guard only; names are not stable subject identity.';

INSERT INTO market.market_object_type_definition(code,name,sort_order)
VALUES ('business-party','经营主体',70);
