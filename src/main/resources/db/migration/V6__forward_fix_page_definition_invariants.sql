DELETE FROM platform.page_action
WHERE code = 'VIEW'
  AND action_scope = 'ROW'
  AND product_code IN ('RICE', 'SOYBEAN')
  AND business_domain = 'MARKET'
  AND page_kind = 'QUALITY';

ALTER TABLE platform.page_column_group_field
    ADD CONSTRAINT page_column_group_field_unique_page_field
    UNIQUE (product_code, business_domain, page_kind, field_code);

ALTER TABLE platform.page_size_option
    DROP CONSTRAINT page_size_option_product_code_business_domain_page_kind_fkey;

ALTER TABLE platform.page_size_option
    ADD CONSTRAINT page_size_option_pagination_fk
    FOREIGN KEY (product_code, business_domain, page_kind)
    REFERENCES platform.page_pagination(product_code, business_domain, page_kind)
    ON DELETE CASCADE
    DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE platform.page_pagination
    ADD CONSTRAINT page_pagination_default_size_fk
    FOREIGN KEY (product_code, business_domain, page_kind, default_page_size)
    REFERENCES platform.page_size_option(product_code, business_domain, page_kind, page_size)
    DEFERRABLE INITIALLY DEFERRED;

CREATE FUNCTION platform.assert_page_has_pagination(
    checked_product_code varchar,
    checked_business_domain varchar,
    checked_page_kind varchar)
RETURNS void
LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM platform.page_presentation presentation
        WHERE presentation.product_code = checked_product_code
          AND presentation.business_domain = checked_business_domain
          AND presentation.page_kind = checked_page_kind
    ) AND NOT EXISTS (
        SELECT 1
        FROM platform.page_pagination pagination
        WHERE pagination.product_code = checked_product_code
          AND pagination.business_domain = checked_business_domain
          AND pagination.page_kind = checked_page_kind
    ) THEN
        RAISE EXCEPTION 'Every page presentation requires pagination configuration';
    END IF;
END;
$$;

CREATE FUNCTION platform.require_page_pagination()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP IN ('DELETE', 'UPDATE') THEN
        PERFORM platform.assert_page_has_pagination(
            OLD.product_code, OLD.business_domain, OLD.page_kind);
    END IF;
    IF TG_OP IN ('INSERT', 'UPDATE') THEN
        PERFORM platform.assert_page_has_pagination(
            NEW.product_code, NEW.business_domain, NEW.page_kind);
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER page_presentation_requires_pagination
AFTER INSERT OR UPDATE OR DELETE ON platform.page_presentation
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION platform.require_page_pagination();

CREATE CONSTRAINT TRIGGER page_pagination_requires_complete_presentation
AFTER INSERT OR UPDATE OR DELETE ON platform.page_pagination
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION platform.require_page_pagination();

COMMENT ON TABLE platform.page_pagination IS
    'Task 3 platform interaction configuration; not business master data; not sourced from the golden screenshot.';
