DROP INDEX market.monitoring_object_one_dossier_per_region_name;

CREATE UNIQUE INDEX monitoring_object_one_dossier_per_subject_name
    ON market.monitoring_object(lower(btrim(object_name)));

COMMENT ON INDEX market.monitoring_object_one_dossier_per_subject_name IS
    'Prevents one market subject name from being split into separate dossiers across regions.';
