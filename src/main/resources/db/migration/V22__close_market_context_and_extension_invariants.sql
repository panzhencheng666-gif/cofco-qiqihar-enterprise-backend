-- V17-V21 are installed and immutable. V22 removes the Round 3 witness field,
-- protects parent-context fact applicability, and makes extension page mounting
-- and extension applicability a deferred bidirectional invariant.

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM market.market_record_core_value witness
        JOIN market.market_record_core_value common
          ON common.record_id = witness.record_id
         AND common.field_code = 'MKT_SOURCE_NOTE'
        WHERE witness.field_code = 'MKT_CORN_SOURCE_NOTE'
    ) THEN
        RAISE EXCEPTION
            'Cannot migrate MKT_CORN_SOURCE_NOTE: a record already has MKT_SOURCE_NOTE';
    END IF;
END;
$$;

-- Preserve any real value created while V21 was deployed by moving it to the
-- permanent generic source-note field. The preflight above prevents overwrites.
UPDATE market.market_record_core_value
SET field_code = 'MKT_SOURCE_NOTE'
WHERE field_code = 'MKT_CORN_SOURCE_NOTE';

DELETE FROM platform.page_column_group_field
WHERE business_domain = 'MARKET'
  AND page_kind = 'MONITORING'
  AND field_code = 'MKT_CORN_SOURCE_NOTE';
DELETE FROM platform.market_core_field_applicability
WHERE field_code = 'MKT_CORN_SOURCE_NOTE';
DELETE FROM platform.page_definition_field
WHERE business_domain = 'MARKET'
  AND page_kind = 'MONITORING'
  AND field_code = 'MKT_CORN_SOURCE_NOTE';
DELETE FROM platform.field_definition
WHERE code = 'MKT_CORN_SOURCE_NOTE';
DELETE FROM platform.market_core_field_definition
WHERE code = 'MKT_CORN_SOURCE_NOTE';

UPDATE platform.market_core_field_definition
SET description = NULL
WHERE code = 'MKT_SOURCE_NOTE';
UPDATE platform.page_column_group_field
SET description = NULL
WHERE business_domain = 'MARKET'
  AND page_kind = 'MONITORING'
  AND field_code = 'MKT_SOURCE_NOTE';

CREATE FUNCTION market.require_record_fact_applicability()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.product_code, NEW.object_type_code)
            IS DISTINCT FROM (OLD.product_code, OLD.object_type_code)
       AND EXISTS (
            SELECT 1
            FROM market.market_record_fact fact
            WHERE fact.record_id = OLD.record_id
              AND NOT EXISTS (
                    SELECT 1
                    FROM platform.market_fact_applicability applicability
                    WHERE applicability.fact_code = fact.fact_code
                      AND applicability.product_code = NEW.product_code
                      AND applicability.object_type_code = NEW.object_type_code
              )
       ) THEN
        RAISE EXCEPTION
            'Market record % has facts that are not applicable to context %/%',
            OLD.record_id, NEW.product_code, NEW.object_type_code;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER market_record_fact_context_guard
BEFORE UPDATE OF product_code, object_type_code ON market.market_record
FOR EACH ROW EXECUTE FUNCTION market.require_record_fact_applicability();

CREATE FUNCTION platform.assert_market_extension_mount_mapping(
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

CREATE FUNCTION platform.require_market_extension_mount_mapping()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        PERFORM platform.assert_market_extension_mount_mapping(
            OLD.product_code, OLD.business_domain, OLD.page_kind, OLD.field_code);
    END IF;
    IF TG_OP <> 'DELETE' THEN
        PERFORM platform.assert_market_extension_mount_mapping(
            NEW.product_code, NEW.business_domain, NEW.page_kind, NEW.field_code);
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER market_extension_page_mount_consistency
AFTER INSERT OR UPDATE OR DELETE ON platform.page_definition_field
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION platform.require_market_extension_mount_mapping();

CREATE CONSTRAINT TRIGGER market_extension_applicability_consistency
AFTER INSERT OR UPDATE OR DELETE ON platform.market_core_field_applicability
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION platform.require_market_extension_mount_mapping();

COMMENT ON FUNCTION market.require_record_fact_applicability() IS
    'Rejects parent market-record context changes that would orphan normalized fact applicability.';
COMMENT ON FUNCTION platform.assert_market_extension_mount_mapping(varchar, varchar, varchar, varchar) IS
    'Deferred bidirectional invariant for MARKET/MONITORING extension page mounts and applicability.';
