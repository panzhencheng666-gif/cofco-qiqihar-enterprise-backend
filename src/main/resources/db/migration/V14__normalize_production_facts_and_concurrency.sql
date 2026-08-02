CREATE TABLE platform.production_fact_definition (
    code varchar(60) PRIMARY KEY,
    category varchar(20) NOT NULL CHECK (category IN ('QUALITY', 'COST', 'INSURANCE', 'SUBSIDY')),
    label varchar(100) NOT NULL,
    value_type varchar(20) NOT NULL CHECK (value_type = 'DECIMAL'),
    unit varchar(40),
    description varchar(240),
    decimal_precision integer NOT NULL CHECK (decimal_precision BETWEEN 1 AND 18),
    decimal_scale integer NOT NULL CHECK (decimal_scale BETWEEN 0 AND decimal_precision),
    UNIQUE (code, category)
);

CREATE TABLE platform.production_fact_applicability (
    fact_code varchar(60) NOT NULL REFERENCES platform.production_fact_definition(code) ON DELETE CASCADE,
    product_code varchar(40) NOT NULL REFERENCES platform.product(code),
    object_type_code varchar(60) REFERENCES platform.object_type(code),
    business_domain varchar(30) NOT NULL,
    page_kind varchar(40) NOT NULL,
    sort_order integer NOT NULL,
    FOREIGN KEY (product_code, business_domain, page_kind)
        REFERENCES platform.page_definition(product_code, business_domain, page_kind) ON DELETE CASCADE,
    FOREIGN KEY (product_code, object_type_code)
        REFERENCES platform.product_object_type(product_code, object_type_code),
    UNIQUE NULLS NOT DISTINCT
        (fact_code, product_code, object_type_code, business_domain, page_kind),
    UNIQUE NULLS NOT DISTINCT
        (product_code, object_type_code, business_domain, page_kind, sort_order)
);

ALTER TABLE platform.cultivar ADD CONSTRAINT cultivar_product_code_code_unique UNIQUE (product_code, code);
ALTER TABLE production.production_record
    ADD COLUMN version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    ADD CONSTRAINT production_record_product_cultivar_fk
        FOREIGN KEY (product_code, cultivar_code) REFERENCES platform.cultivar(product_code, code);

ALTER TABLE production.production_record DROP CONSTRAINT production_record_check;
ALTER TABLE production.production_record ADD CONSTRAINT production_record_survey_date_shanghai_check
    CHECK (survey_date <= (reported_at AT TIME ZONE 'Asia/Shanghai')::date);

ALTER TABLE production.production_record_quality
    DROP CONSTRAINT production_record_quality_quality_code_fkey,
    ADD COLUMN fact_category varchar(20) NOT NULL DEFAULT 'QUALITY' CHECK (fact_category = 'QUALITY');
ALTER TABLE production.production_record_quality
    ADD CONSTRAINT production_quality_definition_fk
        FOREIGN KEY (quality_code, fact_category)
        REFERENCES platform.production_fact_definition(code, category);
ALTER TABLE production.production_record_cost
    ADD COLUMN fact_category varchar(20) NOT NULL DEFAULT 'COST' CHECK (fact_category = 'COST'),
    ADD CONSTRAINT production_cost_definition_fk
        FOREIGN KEY (cost_code, fact_category)
        REFERENCES platform.production_fact_definition(code, category);
ALTER TABLE production.production_record_insurance
    ADD COLUMN fact_category varchar(20) NOT NULL DEFAULT 'INSURANCE' CHECK (fact_category = 'INSURANCE'),
    ADD CONSTRAINT production_insurance_definition_fk
        FOREIGN KEY (insurance_code, fact_category)
        REFERENCES platform.production_fact_definition(code, category);
ALTER TABLE production.production_record_subsidy
    ADD COLUMN fact_category varchar(20) NOT NULL DEFAULT 'SUBSIDY' CHECK (fact_category = 'SUBSIDY'),
    ADD CONSTRAINT production_subsidy_definition_fk
        FOREIGN KEY (subsidy_code, fact_category)
        REFERENCES platform.production_fact_definition(code, category);

CREATE FUNCTION production.require_fact_applicability()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    owner production.production_record%ROWTYPE;
    fact varchar(60);
BEGIN
    SELECT * INTO owner FROM production.production_record WHERE record_id = NEW.record_id;
    fact := CASE TG_TABLE_NAME
        WHEN 'production_record_quality' THEN NEW.quality_code
        WHEN 'production_record_cost' THEN NEW.cost_code
        WHEN 'production_record_insurance' THEN NEW.insurance_code
        WHEN 'production_record_subsidy' THEN NEW.subsidy_code
    END;
    IF NOT EXISTS (
        SELECT 1 FROM platform.production_fact_applicability applicability
        WHERE applicability.fact_code = fact
          AND applicability.product_code = owner.product_code
          AND applicability.business_domain = 'PRODUCTION'
          AND applicability.page_kind = 'MONITORING'
          AND (applicability.object_type_code IS NULL
               OR applicability.object_type_code = owner.object_type_code)
    ) THEN
        RAISE EXCEPTION 'Production fact % is not applicable to record %', fact, NEW.record_id;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER production_quality_applicability BEFORE INSERT OR UPDATE
ON production.production_record_quality FOR EACH ROW EXECUTE FUNCTION production.require_fact_applicability();
CREATE TRIGGER production_cost_applicability BEFORE INSERT OR UPDATE
ON production.production_record_cost FOR EACH ROW EXECUTE FUNCTION production.require_fact_applicability();
CREATE TRIGGER production_insurance_applicability BEFORE INSERT OR UPDATE
ON production.production_record_insurance FOR EACH ROW EXECUTE FUNCTION production.require_fact_applicability();
CREATE TRIGGER production_subsidy_applicability BEFORE INSERT OR UPDATE
ON production.production_record_subsidy FOR EACH ROW EXECUTE FUNCTION production.require_fact_applicability();

COMMENT ON TABLE platform.production_fact_definition IS
    'Definition registry only. V14 deliberately seeds no production business fact definitions or records.';
