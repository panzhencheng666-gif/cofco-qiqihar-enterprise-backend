-- Task 7 review round 3: snapshot-consistent result assembly, immutable formula DAGs,
-- append-only source semantics, and legacy calculation-input compatibility.

CREATE TRIGGER formula_term_referenced_insert_immutable BEFORE INSERT ON supply.formula_term
    FOR EACH ROW EXECUTE FUNCTION supply.reject_referenced_formula_change();

CREATE FUNCTION supply.reject_role_source_applicability_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'source role semantic mapping is immutable; append a new mapping version';
END $$;
CREATE TRIGGER role_source_applicability_immutable BEFORE UPDATE OR DELETE
    ON supply.role_source_applicability FOR EACH ROW
    EXECUTE FUNCTION supply.reject_role_source_applicability_change();

CREATE FUNCTION supply.validate_role_source_applicability_append() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE latest_version integer;
BEGIN
    SELECT max(mapping_version) INTO latest_version
    FROM supply.role_source_applicability mapping
    WHERE mapping.product_code=NEW.product_code AND mapping.role_code=NEW.role_code
      AND mapping.source_domain=NEW.source_domain AND mapping.source_field_code=NEW.source_field_code
      AND mapping.source_unit_code=NEW.source_unit_code
      AND mapping.required_direction_code IS NOT DISTINCT FROM NEW.required_direction_code;
    IF latest_version IS NULL AND NEW.mapping_version<>1 THEN
        RAISE EXCEPTION 'first source role semantic mapping must start at version 1';
    ELSIF latest_version IS NOT NULL AND NEW.mapping_version<>latest_version+1 THEN
        RAISE EXCEPTION 'source role semantic mapping must append the next version';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER role_source_applicability_append BEFORE INSERT
    ON supply.role_source_applicability FOR EACH ROW
    EXECUTE FUNCTION supply.validate_role_source_applicability_append();

ALTER TABLE supply.source_adoption_set
    ADD COLUMN legacy boolean NOT NULL DEFAULT false;

CREATE OR REPLACE FUNCTION supply.validate_source_adoption_set() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE selected_set uuid:=COALESCE(NEW.input_set_id,OLD.input_set_id);
DECLARE legacy_set boolean:=false;
BEGIN
    SELECT legacy INTO legacy_set FROM supply.source_adoption_set WHERE input_set_id=selected_set;
    IF EXISTS(SELECT 1 FROM supply.source_adoption_set adoption_set WHERE adoption_set.input_set_id=selected_set)
       AND NOT COALESCE(legacy_set,false)
       AND EXISTS(
           SELECT 1 FROM supply.account_input_role role WHERE role.required
           AND NOT EXISTS(SELECT 1 FROM supply.source_adoption_set_item item
                          WHERE item.input_set_id=selected_set AND item.role_code=role.role_code)) THEN
        RAISE EXCEPTION 'source adoption set is missing required roles';
    END IF;
    IF EXISTS(
        SELECT 1 FROM supply.source_adoption_set_item item
        JOIN supply.source_adoption_set adoption_set ON adoption_set.input_set_id=item.input_set_id
        JOIN supply.source_release release ON release.source_release_id=item.source_release_id
        WHERE item.input_set_id=selected_set
          AND (release.product_code<>adoption_set.product_code OR release.region_code<>adoption_set.region_code
               OR release.marketing_year<>adoption_set.marketing_year OR release.approval_state<>'APPROVED')) THEN
        RAISE EXCEPTION 'source adoption set context does not match approved releases';
    END IF;
    RETURN NULL;
END $$;

-- One immutable legacy input set is created for every pre-V27 run whose snapshotted
-- source releases can be represented by the V27 uniqueness rules.  Any exceptional
-- historical run remains NULL and is exposed as an explicitly read-only legacy result.
WITH candidates AS (
    SELECT run.calculation_run_id,run.product_code,run.region_code,run.marketing_year,
           run.created_by,run.created_at,
           md5('legacy-input-set:'||run.calculation_run_id::text)::uuid input_set_id,
           row_number() OVER (
               PARTITION BY run.product_code,run.region_code,run.marketing_year
               ORDER BY run.created_at,run.calculation_run_id) legacy_ordinal
    FROM supply.calculation_run run
    WHERE run.input_set_id IS NULL
      AND EXISTS(SELECT 1 FROM supply.calculation_source_reference reference
                 WHERE reference.calculation_run_id=run.calculation_run_id)
      AND NOT EXISTS(
          SELECT 1 FROM supply.calculation_source_reference reference
          LEFT JOIN supply.source_release_binding binding
            ON binding.source_release_id=reference.source_release_id AND binding.role_code=reference.role_code
          WHERE reference.calculation_run_id=run.calculation_run_id AND binding.source_release_id IS NULL)
      AND NOT EXISTS(
          SELECT 1 FROM supply.calculation_source_reference reference
          WHERE reference.calculation_run_id=run.calculation_run_id
          GROUP BY reference.source_release_id HAVING count(*)>1)
      AND NOT EXISTS(
          SELECT 1 FROM supply.calculation_source_reference reference
          WHERE reference.calculation_run_id=run.calculation_run_id
          GROUP BY reference.source_domain_snapshot,reference.source_record_id,reference.source_version,
                   reference.source_field_code_snapshot HAVING count(*)>1)
), numbered AS (
    SELECT candidate.*,
           COALESCE((SELECT max(existing.version_no) FROM supply.source_adoption_set existing
                     WHERE existing.product_code=candidate.product_code
                       AND existing.region_code=candidate.region_code
                       AND existing.marketing_year=candidate.marketing_year),0)+candidate.legacy_ordinal version_no
    FROM candidates candidate
)
INSERT INTO supply.source_adoption_set(
    input_set_id,version_no,product_code,region_code,marketing_year,reason,created_by,created_at,legacy)
SELECT input_set_id,version_no,product_code,region_code,marketing_year,
       '历史运行来源快照回填',created_by,created_at,true
FROM numbered;

INSERT INTO supply.source_adoption_set_item(
    input_set_id,role_code,source_release_id,source_domain,source_record_id,source_version,source_field_code)
SELECT adoption_set.input_set_id,reference.role_code,reference.source_release_id,
       reference.source_domain_snapshot,reference.source_record_id,reference.source_version,
       reference.source_field_code_snapshot
FROM supply.source_adoption_set adoption_set
JOIN supply.calculation_run run
  ON adoption_set.input_set_id=md5('legacy-input-set:'||run.calculation_run_id::text)::uuid
JOIN supply.calculation_source_reference reference ON reference.calculation_run_id=run.calculation_run_id
WHERE adoption_set.legacy AND run.input_set_id IS NULL;

UPDATE supply.calculation_run run
SET input_set_id=adoption_set.input_set_id
FROM supply.source_adoption_set adoption_set
WHERE run.input_set_id IS NULL AND adoption_set.legacy
  AND adoption_set.input_set_id=md5('legacy-input-set:'||run.calculation_run_id::text)::uuid
  AND EXISTS(SELECT 1 FROM supply.source_adoption_set_item item
             WHERE item.input_set_id=adoption_set.input_set_id);

COMMENT ON COLUMN supply.source_adoption_set.legacy IS
    'True only for immutable V25/V26 calculation-source snapshot backfill; it is never eligible for a new run.';
COMMENT ON COLUMN supply.calculation_run.input_set_id IS
    'Explicit immutable input set. NULL only denotes a non-representable historical read-only V25/V26 snapshot.';
