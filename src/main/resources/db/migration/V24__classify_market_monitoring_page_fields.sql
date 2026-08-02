-- V17-V23 are installed and immutable. V24 gives every MARKET/MONITORING
-- page field an explicit database-owned source and protects the complete graph.

CREATE TABLE platform.market_monitoring_projection_field_definition (
    field_code varchar(60) PRIMARY KEY,
    projection_kind varchar(40) NOT NULL CHECK (btrim(projection_kind) <> ''),
    required_on_page boolean NOT NULL DEFAULT false,
    CONSTRAINT market_monitoring_projection_field_definition_field_fk
        FOREIGN KEY (field_code) REFERENCES platform.field_definition(code)
        DEFERRABLE INITIALLY DEFERRED
);

INSERT INTO platform.market_monitoring_projection_field_definition(
    field_code, projection_kind, required_on_page)
VALUES ('MKT_STATUS', 'RECORD_STATUS', true);

CREATE TABLE platform.market_core_typed_binding_requirement (
    domain_binding varchar(40) PRIMARY KEY
);

INSERT INTO platform.market_core_typed_binding_requirement(domain_binding) VALUES
    ('OBJECT_TYPE'), ('REGION'), ('TRADE_DATE'), ('REPORTED_AT'),
    ('TRADE_DIRECTION'), ('PURCHASE_BASE_PRICE'), ('SALE_BASE_PRICE'),
    ('CARRIAGE_BOARD_AMOUNT'), ('PACKAGING_FORM'), ('PACKAGING_AMOUNT'),
    ('FREIGHT_AMOUNT'), ('ACTUAL_TRADE_PRICE');

CREATE FUNCTION platform.assert_market_monitoring_definition_graph()
RETURNS void LANGUAGE plpgsql AS $$
DECLARE
    violation record;
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended(
        'platform.market-monitoring-definition-graph', 0));

    SELECT page_field.product_code, page_field.field_code,
           (core.code IS NOT NULL) core_source,
           (fact.code IS NOT NULL) fact_source,
           (projection.field_code IS NOT NULL) projection_source
    INTO violation
    FROM platform.page_definition_field page_field
    LEFT JOIN platform.market_core_field_definition core
      ON core.code = page_field.field_code
    LEFT JOIN platform.market_fact_definition fact
      ON fact.code = page_field.field_code
    LEFT JOIN platform.market_monitoring_projection_field_definition projection
      ON projection.field_code = page_field.field_code
    WHERE page_field.business_domain = 'MARKET'
      AND page_field.page_kind = 'MONITORING'
      AND (core.code IS NOT NULL)::integer
        + (fact.code IS NOT NULL)::integer
        + (projection.field_code IS NOT NULL)::integer <> 1
    ORDER BY page_field.product_code, page_field.field_code
    LIMIT 1;

    IF FOUND THEN
        RAISE EXCEPTION
            'Market page field source invariant failed: product=%, field=%, core=%, fact=%, projection=%',
            violation.product_code, violation.field_code,
            violation.core_source, violation.fact_source, violation.projection_source;
    END IF;

    SELECT requirement.domain_binding, count(core.code) definition_count
    INTO violation
    FROM platform.market_core_typed_binding_requirement requirement
    LEFT JOIN platform.market_core_field_definition core
      ON core.domain_binding = requirement.domain_binding
    GROUP BY requirement.domain_binding
    HAVING count(core.code) <> 1
    ORDER BY requirement.domain_binding
    LIMIT 1;

    IF FOUND THEN
        RAISE EXCEPTION
            'Market typed binding cardinality invariant failed: binding=%, definitions=%',
            violation.domain_binding, violation.definition_count;
    END IF;

    SELECT core.domain_binding, count(*) definition_count
    INTO violation
    FROM platform.market_core_field_definition core
    LEFT JOIN platform.market_core_typed_binding_requirement requirement
      ON requirement.domain_binding = core.domain_binding
    WHERE core.domain_binding <> 'EXTENSION'
      AND requirement.domain_binding IS NULL
    GROUP BY core.domain_binding
    ORDER BY core.domain_binding
    LIMIT 1;

    IF FOUND THEN
        RAISE EXCEPTION
            'Market typed binding is not declared: binding=%, definitions=%',
            violation.domain_binding, violation.definition_count;
    END IF;

    SELECT page.product_code, requirement.domain_binding, count(core.code) mounted_count
    INTO violation
    FROM platform.page_definition page
    CROSS JOIN platform.market_core_typed_binding_requirement requirement
    LEFT JOIN platform.page_definition_field page_field
      ON page_field.page_definition_id = page.page_definition_id
    LEFT JOIN platform.market_core_field_definition core
      ON core.code = page_field.field_code
     AND core.domain_binding = requirement.domain_binding
    WHERE page.business_domain = 'MARKET'
      AND page.page_kind = 'MONITORING'
    GROUP BY page.product_code, requirement.domain_binding
    HAVING count(core.code) <> 1
    ORDER BY page.product_code, requirement.domain_binding
    LIMIT 1;

    IF FOUND THEN
        RAISE EXCEPTION
            'Market typed binding page mount invariant failed: product=%, binding=%, mounts=%',
            violation.product_code, violation.domain_binding, violation.mounted_count;
    END IF;

    SELECT page.product_code, projection.field_code, count(page_field.field_code) mounted_count
    INTO violation
    FROM platform.page_definition page
    CROSS JOIN platform.market_monitoring_projection_field_definition projection
    LEFT JOIN platform.page_definition_field page_field
      ON page_field.page_definition_id = page.page_definition_id
     AND page_field.field_code = projection.field_code
    WHERE page.business_domain = 'MARKET'
      AND page.page_kind = 'MONITORING'
      AND projection.required_on_page
    GROUP BY page.product_code, projection.field_code
    HAVING count(page_field.field_code) <> 1
    ORDER BY page.product_code, projection.field_code
    LIMIT 1;

    IF FOUND THEN
        RAISE EXCEPTION
            'Market projection page mount invariant failed: product=%, field=%, mounts=%',
            violation.product_code, violation.field_code, violation.mounted_count;
    END IF;
END;
$$;

DO $$
BEGIN
    BEGIN
        PERFORM platform.assert_market_monitoring_definition_graph();
    EXCEPTION WHEN OTHERS THEN
        RAISE EXCEPTION 'V24 preflight market page field source invariant failed: %', SQLERRM;
    END;
END;
$$;

CREATE FUNCTION platform.require_market_monitoring_definition_graph()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM platform.assert_market_monitoring_definition_graph();
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER market_page_field_definition_graph
AFTER INSERT OR UPDATE OR DELETE ON platform.page_definition_field
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION platform.require_market_monitoring_definition_graph();

CREATE CONSTRAINT TRIGGER market_page_definition_graph
AFTER INSERT OR UPDATE OR DELETE ON platform.page_definition
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION platform.require_market_monitoring_definition_graph();

CREATE CONSTRAINT TRIGGER market_core_definition_graph
AFTER INSERT OR UPDATE OR DELETE ON platform.market_core_field_definition
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION platform.require_market_monitoring_definition_graph();

CREATE CONSTRAINT TRIGGER market_fact_definition_graph
AFTER INSERT OR UPDATE OR DELETE ON platform.market_fact_definition
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION platform.require_market_monitoring_definition_graph();

CREATE CONSTRAINT TRIGGER market_projection_definition_graph
AFTER INSERT OR UPDATE OR DELETE ON platform.market_monitoring_projection_field_definition
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION platform.require_market_monitoring_definition_graph();

CREATE CONSTRAINT TRIGGER market_typed_binding_requirement_graph
AFTER INSERT OR UPDATE OR DELETE ON platform.market_core_typed_binding_requirement
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION platform.require_market_monitoring_definition_graph();

COMMENT ON TABLE platform.market_monitoring_projection_field_definition IS
    'Explicit database-owned SYSTEM_PROJECTION source classification for MARKET/MONITORING page fields.';
COMMENT ON TABLE platform.market_core_typed_binding_requirement IS
    'Required typed core bindings; each definition and each MARKET/MONITORING page must contain exactly one.';
