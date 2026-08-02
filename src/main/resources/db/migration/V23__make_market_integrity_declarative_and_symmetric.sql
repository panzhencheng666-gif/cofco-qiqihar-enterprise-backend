-- V17-V22 are installed and immutable. V23 moves fact/context integrity to
-- deferred composite foreign keys and closes definition-side extension bypasses.

DO $$
DECLARE
    violation record;
BEGIN
    SELECT candidate.product_code, candidate.field_code,
           candidate.domain_binding, candidate.mounted, candidate.mapped
    INTO violation
    FROM (
        SELECT pair.product_code, pair.field_code, definition.domain_binding,
               EXISTS (
                   SELECT 1 FROM platform.page_definition_field page_field
                   WHERE page_field.product_code = pair.product_code
                     AND page_field.business_domain = 'MARKET'
                     AND page_field.page_kind = 'MONITORING'
                     AND page_field.field_code = pair.field_code
               ) mounted,
               EXISTS (
                   SELECT 1 FROM platform.market_core_field_applicability applicability
                   WHERE applicability.product_code = pair.product_code
                     AND applicability.business_domain = 'MARKET'
                     AND applicability.page_kind = 'MONITORING'
                     AND applicability.field_code = pair.field_code
               ) mapped
        FROM (
            SELECT product_code, field_code
            FROM platform.page_definition_field
            WHERE business_domain = 'MARKET' AND page_kind = 'MONITORING'
            UNION
            SELECT product_code, field_code
            FROM platform.market_core_field_applicability
        ) pair
        LEFT JOIN platform.market_core_field_definition definition
          ON definition.code = pair.field_code
    ) candidate
    WHERE (candidate.domain_binding = 'EXTENSION'
              AND candidate.mounted IS DISTINCT FROM candidate.mapped)
       OR (candidate.domain_binding IS DISTINCT FROM 'EXTENSION' AND candidate.mapped)
    ORDER BY candidate.product_code, candidate.field_code
    LIMIT 1;

    IF FOUND THEN
        RAISE EXCEPTION
            'V23 preflight extension invariant failed: product=%, field=%, binding=%, mounted=%, mapped=%',
            violation.product_code, violation.field_code,
            coalesce(violation.domain_binding, '<missing>'),
            violation.mounted, violation.mapped;
    END IF;

    SELECT fact.record_id, record.product_code, record.object_type_code, fact.fact_code
    INTO violation
    FROM market.market_record_fact fact
    JOIN market.market_record record ON record.record_id = fact.record_id
    LEFT JOIN platform.market_fact_applicability applicability
      ON applicability.product_code = record.product_code
     AND applicability.object_type_code = record.object_type_code
     AND applicability.fact_code = fact.fact_code
    WHERE applicability.fact_code IS NULL
    ORDER BY fact.record_id, fact.fact_code
    LIMIT 1;

    IF FOUND THEN
        RAISE EXCEPTION
            'V23 preflight fact applicability failed: record=%, product=%, object=%, fact=%',
            violation.record_id, violation.product_code,
            violation.object_type_code, violation.fact_code;
    END IF;
END;
$$;

ALTER TABLE platform.market_core_field_applicability
    DROP CONSTRAINT market_core_field_applicabili_product_code_business_domain_fkey,
    DROP CONSTRAINT market_core_field_applicability_field_code_domain_binding_fkey,
    ADD CONSTRAINT market_core_field_applicabili_product_code_business_domain_fkey
        FOREIGN KEY (product_code, business_domain, page_kind, field_code)
        REFERENCES platform.page_definition_field(
            product_code, business_domain, page_kind, field_code)
        DEFERRABLE INITIALLY DEFERRED,
    ADD CONSTRAINT market_core_field_applicability_field_code_domain_binding_fkey
        FOREIGN KEY (field_code, domain_binding)
        REFERENCES platform.market_core_field_definition(code, domain_binding)
        DEFERRABLE INITIALLY DEFERRED;

CREATE OR REPLACE FUNCTION platform.assert_market_extension_mount_mapping(
    checked_product varchar,
    checked_domain varchar,
    checked_page_kind varchar,
    checked_field varchar)
RETURNS void LANGUAGE plpgsql AS $$
DECLARE
    extension_definition boolean;
    mount_count integer;
    mapping_count integer;
BEGIN
    IF checked_domain <> 'MARKET' OR checked_page_kind <> 'MONITORING' THEN
        RETURN;
    END IF;

    PERFORM pg_advisory_xact_lock(hashtextextended(
        concat_ws('|', checked_product, checked_domain, checked_page_kind, checked_field), 0));

    SELECT definition.domain_binding = 'EXTENSION'
    INTO extension_definition
    FROM platform.market_core_field_definition definition
    WHERE definition.code = checked_field;

    SELECT count(*) INTO mount_count
    FROM platform.page_definition_field page_field
    WHERE page_field.product_code = checked_product
      AND page_field.business_domain = checked_domain
      AND page_field.page_kind = checked_page_kind
      AND page_field.field_code = checked_field;

    SELECT count(*) INTO mapping_count
    FROM platform.market_core_field_applicability applicability
    WHERE applicability.product_code = checked_product
      AND applicability.business_domain = checked_domain
      AND applicability.page_kind = checked_page_kind
      AND applicability.field_code = checked_field;

    IF coalesce(extension_definition, false) AND mount_count <> mapping_count THEN
        RAISE EXCEPTION
            'Market extension % mount/applicability mismatch for product %',
            checked_field, checked_product;
    END IF;
    IF NOT coalesce(extension_definition, false) AND mapping_count <> 0 THEN
        RAISE EXCEPTION
            'Non-extension field % cannot have market extension applicability',
            checked_field;
    END IF;
END;
$$;

CREATE FUNCTION platform.require_market_core_definition_consistency()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    pair record;
    old_code varchar;
    new_code varchar;
    final_binding varchar;
    mount_count integer;
BEGIN
    old_code := CASE WHEN TG_OP = 'INSERT' THEN NULL ELSE OLD.code END;
    new_code := CASE WHEN TG_OP = 'DELETE' THEN NULL ELSE NEW.code END;

    FOR pair IN
        SELECT DISTINCT candidate.product_code, candidate.business_domain,
               candidate.page_kind, candidate.field_code
        FROM (
            SELECT page_field.product_code, page_field.business_domain,
                   page_field.page_kind, page_field.field_code
            FROM platform.page_definition_field page_field
            WHERE page_field.field_code IN (old_code, new_code)
            UNION ALL
            SELECT applicability.product_code, applicability.business_domain,
                   applicability.page_kind, applicability.field_code
            FROM platform.market_core_field_applicability applicability
            WHERE applicability.field_code IN (old_code, new_code)
        ) candidate
        ORDER BY candidate.product_code, candidate.business_domain,
                 candidate.page_kind, candidate.field_code
    LOOP
        SELECT definition.domain_binding INTO final_binding
        FROM platform.market_core_field_definition definition
        WHERE definition.code = pair.field_code;

        SELECT count(*) INTO mount_count
        FROM platform.page_definition_field page_field
        WHERE page_field.product_code = pair.product_code
          AND page_field.business_domain = pair.business_domain
          AND page_field.page_kind = pair.page_kind
          AND page_field.field_code = pair.field_code;

        IF final_binding IS NULL AND mount_count <> 0 THEN
            RAISE EXCEPTION
                'Mounted market core field % has no core definition for product %',
                pair.field_code, pair.product_code;
        END IF;

        PERFORM platform.assert_market_extension_mount_mapping(
            pair.product_code, pair.business_domain, pair.page_kind, pair.field_code);
    END LOOP;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER market_core_definition_mount_consistency
AFTER INSERT OR UPDATE OR DELETE ON platform.market_core_field_definition
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION platform.require_market_core_definition_consistency();

DROP TRIGGER market_record_fact_context_guard ON market.market_record;
DROP FUNCTION market.require_record_fact_applicability();
DROP TRIGGER market_fact_applicability ON market.market_record_fact;
DROP FUNCTION market.require_fact_applicability();

ALTER TABLE market.market_record_fact
    ADD COLUMN product_code varchar(40),
    ADD COLUMN object_type_code varchar(60);

UPDATE market.market_record_fact fact
SET product_code = record.product_code,
    object_type_code = record.object_type_code
FROM market.market_record record
WHERE record.record_id = fact.record_id;

ALTER TABLE market.market_record_fact
    ALTER COLUMN product_code SET NOT NULL,
    ALTER COLUMN object_type_code SET NOT NULL;

ALTER TABLE market.market_record
    ADD CONSTRAINT market_record_id_product_object_unique
        UNIQUE (record_id, product_code, object_type_code);

ALTER TABLE platform.market_fact_applicability
    ADD CONSTRAINT market_fact_applicability_context_fact_unique
        UNIQUE (product_code, object_type_code, fact_code);

ALTER TABLE market.market_record_fact
    DROP CONSTRAINT market_record_fact_record_id_fkey,
    ADD CONSTRAINT market_record_fact_header_context_fk
        FOREIGN KEY (record_id, product_code, object_type_code)
        REFERENCES market.market_record(record_id, product_code, object_type_code)
        ON UPDATE CASCADE ON DELETE CASCADE
        DEFERRABLE INITIALLY DEFERRED,
    ADD CONSTRAINT market_record_fact_applicability_fk
        FOREIGN KEY (product_code, object_type_code, fact_code)
        REFERENCES platform.market_fact_applicability(
            product_code, object_type_code, fact_code)
        DEFERRABLE INITIALLY DEFERRED;

COMMENT ON COLUMN market.market_record_fact.product_code IS
    'Denormalized owner context protected by a deferred composite FK to the record header.';
COMMENT ON COLUMN market.market_record_fact.object_type_code IS
    'Denormalized owner context protected by deferred header and applicability FKs.';
COMMENT ON CONSTRAINT market_record_fact_applicability_fk ON market.market_record_fact IS
    'Declarative bidirectional protection against deleting or moving referenced applicability.';
