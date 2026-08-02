ALTER TABLE platform.business_batch
    ADD CONSTRAINT business_batch_code_period_unique
    UNIQUE (code, business_period_code);

ALTER TABLE platform.page_default_context
    ADD CONSTRAINT page_default_batch_requires_period
    CHECK (
        default_business_batch_code IS NULL
        OR default_business_period_code IS NOT NULL
    ),
    ADD CONSTRAINT page_default_batch_period_fk
    FOREIGN KEY (default_business_batch_code, default_business_period_code)
    REFERENCES platform.business_batch(code, business_period_code),
    ADD CONSTRAINT page_default_product_definition_fk
    FOREIGN KEY (default_product_code, business_domain, page_kind)
    REFERENCES platform.page_definition(product_code, business_domain, page_kind);

ALTER TABLE platform.region
    ADD CONSTRAINT region_hierarchy_shape
    CHECK (
        (administrative_level = 'PREFECTURE' AND parent_code IS NULL)
        OR (
            administrative_level = 'COUNTY'
            AND parent_code IS NOT NULL
            AND parent_code <> code
        )
    );

CREATE FUNCTION platform.enforce_region_parent_level()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.administrative_level = 'COUNTY'
       AND NOT EXISTS (
           SELECT 1
           FROM platform.region parent
           WHERE parent.code = NEW.parent_code
             AND parent.administrative_level = 'PREFECTURE'
       ) THEN
        RAISE EXCEPTION 'COUNTY parent must be a PREFECTURE'
            USING ERRCODE = '23514';
    END IF;

    IF NEW.administrative_level <> 'PREFECTURE'
       AND EXISTS (
           SELECT 1
           FROM platform.region child
           WHERE child.parent_code = NEW.code
       ) THEN
        RAISE EXCEPTION 'A region with children must remain a PREFECTURE'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;

CREATE CONSTRAINT TRIGGER region_parent_level_invariant
AFTER INSERT OR UPDATE ON platform.region
DEFERRABLE INITIALLY IMMEDIATE
FOR EACH ROW
EXECUTE FUNCTION platform.enforce_region_parent_level();
