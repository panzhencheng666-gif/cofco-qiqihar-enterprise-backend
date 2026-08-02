CREATE OR REPLACE FUNCTION production.require_fact_applicability()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    owner production.production_record%ROWTYPE;
    fact varchar(60);
BEGIN
    SELECT * INTO owner FROM production.production_record WHERE record_id = NEW.record_id;
    fact := CASE TG_TABLE_NAME
        WHEN 'production_record_quality' THEN to_jsonb(NEW) ->> 'quality_code'
        WHEN 'production_record_cost' THEN to_jsonb(NEW) ->> 'cost_code'
        WHEN 'production_record_insurance' THEN to_jsonb(NEW) ->> 'insurance_code'
        WHEN 'production_record_subsidy' THEN to_jsonb(NEW) ->> 'subsidy_code'
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
