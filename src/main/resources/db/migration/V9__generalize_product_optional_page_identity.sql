DROP TRIGGER page_presentation_requires_pagination ON platform.page_presentation;
DROP TRIGGER page_pagination_requires_complete_presentation ON platform.page_pagination;

ALTER TABLE platform.page_default_context
    DROP CONSTRAINT page_default_product_definition_fk;
ALTER TABLE platform.page_definition_field
    DROP CONSTRAINT page_definition_field_product_code_business_domain_page_ki_fkey;
ALTER TABLE platform.page_presentation
    DROP CONSTRAINT page_presentation_product_code_business_domain_page_kind_fkey;
ALTER TABLE platform.page_breadcrumb
    DROP CONSTRAINT page_breadcrumb_product_code_business_domain_page_kind_fkey;
ALTER TABLE platform.page_filter_definition
    DROP CONSTRAINT page_filter_definition_product_code_business_domain_page_k_fkey;
ALTER TABLE platform.page_filter_option
    DROP CONSTRAINT page_filter_option_product_code_business_domain_page_kind__fkey;
ALTER TABLE platform.page_default_value
    DROP CONSTRAINT page_default_value_product_code_business_domain_page_kind__fkey;
ALTER TABLE platform.page_column_group
    DROP CONSTRAINT page_column_group_product_code_business_domain_page_kind_fkey;
ALTER TABLE platform.page_column_group_field
    DROP CONSTRAINT page_column_group_field_product_code_business_domain_page__fkey,
    DROP CONSTRAINT page_column_group_field_product_code_business_domain_page_fkey1;
ALTER TABLE platform.page_action
    DROP CONSTRAINT page_action_product_code_business_domain_page_kind_fkey;
ALTER TABLE platform.page_pagination
    DROP CONSTRAINT page_pagination_product_code_business_domain_page_kind_fkey,
    DROP CONSTRAINT page_pagination_default_size_fk;
ALTER TABLE platform.page_size_option
    DROP CONSTRAINT page_size_option_pagination_fk;
ALTER TABLE market.market_record_projection
    DROP CONSTRAINT market_record_projection_product_code_business_domain_page_fkey;

ALTER TABLE platform.page_definition
    ADD COLUMN page_definition_id bigint GENERATED ALWAYS AS IDENTITY;
ALTER TABLE platform.page_presentation
    ADD COLUMN page_presentation_id bigint GENERATED ALWAYS AS IDENTITY,
    ADD COLUMN page_definition_id bigint;
ALTER TABLE platform.page_definition_field ADD COLUMN page_definition_id bigint;
ALTER TABLE platform.page_breadcrumb ADD COLUMN page_presentation_id bigint;
ALTER TABLE platform.page_filter_definition ADD COLUMN page_presentation_id bigint;
ALTER TABLE platform.page_filter_option ADD COLUMN page_presentation_id bigint;
ALTER TABLE platform.page_default_value ADD COLUMN page_presentation_id bigint;
ALTER TABLE platform.page_column_group ADD COLUMN page_presentation_id bigint;
ALTER TABLE platform.page_column_group_field
    ADD COLUMN page_presentation_id bigint,
    ADD COLUMN page_definition_id bigint;
ALTER TABLE platform.page_action ADD COLUMN page_presentation_id bigint;
ALTER TABLE platform.page_pagination ADD COLUMN page_presentation_id bigint;
ALTER TABLE platform.page_size_option ADD COLUMN page_presentation_id bigint;
ALTER TABLE market.market_record_projection ADD COLUMN page_presentation_id bigint;

UPDATE platform.page_presentation presentation
SET page_definition_id = definition.page_definition_id
FROM platform.page_definition definition
WHERE definition.product_code = presentation.product_code
  AND definition.business_domain = presentation.business_domain
  AND definition.page_kind = presentation.page_kind;

UPDATE platform.page_definition_field child
SET page_definition_id = definition.page_definition_id
FROM platform.page_definition definition
WHERE definition.product_code = child.product_code
  AND definition.business_domain = child.business_domain
  AND definition.page_kind = child.page_kind;

UPDATE platform.page_breadcrumb child
SET page_presentation_id = presentation.page_presentation_id
FROM platform.page_presentation presentation
WHERE presentation.product_code = child.product_code
  AND presentation.business_domain = child.business_domain
  AND presentation.page_kind = child.page_kind;
UPDATE platform.page_filter_definition child
SET page_presentation_id = presentation.page_presentation_id
FROM platform.page_presentation presentation
WHERE presentation.product_code = child.product_code
  AND presentation.business_domain = child.business_domain
  AND presentation.page_kind = child.page_kind;
UPDATE platform.page_filter_option child
SET page_presentation_id = presentation.page_presentation_id
FROM platform.page_presentation presentation
WHERE presentation.product_code = child.product_code
  AND presentation.business_domain = child.business_domain
  AND presentation.page_kind = child.page_kind;
UPDATE platform.page_default_value child
SET page_presentation_id = presentation.page_presentation_id
FROM platform.page_presentation presentation
WHERE presentation.product_code = child.product_code
  AND presentation.business_domain = child.business_domain
  AND presentation.page_kind = child.page_kind;
UPDATE platform.page_column_group child
SET page_presentation_id = presentation.page_presentation_id
FROM platform.page_presentation presentation
WHERE presentation.product_code = child.product_code
  AND presentation.business_domain = child.business_domain
  AND presentation.page_kind = child.page_kind;
UPDATE platform.page_column_group_field child
SET page_presentation_id = presentation.page_presentation_id,
    page_definition_id = presentation.page_definition_id
FROM platform.page_presentation presentation
WHERE presentation.product_code = child.product_code
  AND presentation.business_domain = child.business_domain
  AND presentation.page_kind = child.page_kind;
UPDATE platform.page_action child
SET page_presentation_id = presentation.page_presentation_id
FROM platform.page_presentation presentation
WHERE presentation.product_code = child.product_code
  AND presentation.business_domain = child.business_domain
  AND presentation.page_kind = child.page_kind;
UPDATE platform.page_pagination child
SET page_presentation_id = presentation.page_presentation_id
FROM platform.page_presentation presentation
WHERE presentation.product_code = child.product_code
  AND presentation.business_domain = child.business_domain
  AND presentation.page_kind = child.page_kind;
UPDATE platform.page_size_option child
SET page_presentation_id = presentation.page_presentation_id
FROM platform.page_presentation presentation
WHERE presentation.product_code = child.product_code
  AND presentation.business_domain = child.business_domain
  AND presentation.page_kind = child.page_kind;
UPDATE market.market_record_projection child
SET page_presentation_id = presentation.page_presentation_id
FROM platform.page_presentation presentation
WHERE presentation.product_code = child.product_code
  AND presentation.business_domain = child.business_domain
  AND presentation.page_kind = child.page_kind;

ALTER TABLE platform.page_presentation ALTER COLUMN page_definition_id SET NOT NULL;
ALTER TABLE platform.page_definition_field ALTER COLUMN page_definition_id SET NOT NULL;
ALTER TABLE platform.page_breadcrumb ALTER COLUMN page_presentation_id SET NOT NULL;
ALTER TABLE platform.page_filter_definition ALTER COLUMN page_presentation_id SET NOT NULL;
ALTER TABLE platform.page_filter_option ALTER COLUMN page_presentation_id SET NOT NULL;
ALTER TABLE platform.page_default_value ALTER COLUMN page_presentation_id SET NOT NULL;
ALTER TABLE platform.page_column_group ALTER COLUMN page_presentation_id SET NOT NULL;
ALTER TABLE platform.page_column_group_field
    ALTER COLUMN page_presentation_id SET NOT NULL,
    ALTER COLUMN page_definition_id SET NOT NULL;
ALTER TABLE platform.page_action ALTER COLUMN page_presentation_id SET NOT NULL;
ALTER TABLE platform.page_pagination ALTER COLUMN page_presentation_id SET NOT NULL;
ALTER TABLE platform.page_size_option ALTER COLUMN page_presentation_id SET NOT NULL;
ALTER TABLE market.market_record_projection ALTER COLUMN page_presentation_id SET NOT NULL;

ALTER TABLE platform.page_definition DROP CONSTRAINT page_definition_pkey;
ALTER TABLE platform.page_definition_field
    DROP CONSTRAINT page_definition_field_pkey,
    DROP CONSTRAINT page_definition_field_product_code_business_domain_page_kin_key;
ALTER TABLE platform.page_presentation DROP CONSTRAINT page_presentation_pkey;
ALTER TABLE platform.page_breadcrumb
    DROP CONSTRAINT page_breadcrumb_pkey,
    DROP CONSTRAINT page_breadcrumb_product_code_business_domain_page_kind_sort_key;
ALTER TABLE platform.page_filter_definition
    DROP CONSTRAINT page_filter_definition_pkey,
    DROP CONSTRAINT page_filter_definition_product_code_business_domain_page_ki_key;
ALTER TABLE platform.page_filter_option
    DROP CONSTRAINT page_filter_option_pkey,
    DROP CONSTRAINT page_filter_option_product_code_business_domain_page_kind_f_key;
ALTER TABLE platform.page_default_value DROP CONSTRAINT page_default_value_pkey;
ALTER TABLE platform.page_column_group
    DROP CONSTRAINT page_column_group_pkey,
    DROP CONSTRAINT page_column_group_product_code_business_domain_page_kind_so_key;
ALTER TABLE platform.page_column_group_field
    DROP CONSTRAINT page_column_group_field_pkey,
    DROP CONSTRAINT page_column_group_field_product_code_business_domain_page_k_key,
    DROP CONSTRAINT page_column_group_field_unique_page_field;
ALTER TABLE platform.page_action
    DROP CONSTRAINT page_action_pkey,
    DROP CONSTRAINT page_action_product_code_business_domain_page_kind_sort_ord_key;
ALTER TABLE platform.page_pagination DROP CONSTRAINT page_pagination_pkey;
ALTER TABLE platform.page_size_option
    DROP CONSTRAINT page_size_option_pkey,
    DROP CONSTRAINT page_size_option_product_code_business_domain_page_kind_sor_key;

ALTER TABLE platform.page_definition ALTER COLUMN product_code DROP NOT NULL;
ALTER TABLE platform.page_definition_field ALTER COLUMN product_code DROP NOT NULL;
ALTER TABLE platform.page_presentation ALTER COLUMN product_code DROP NOT NULL;
ALTER TABLE platform.page_breadcrumb ALTER COLUMN product_code DROP NOT NULL;
ALTER TABLE platform.page_filter_definition ALTER COLUMN product_code DROP NOT NULL;
ALTER TABLE platform.page_filter_option ALTER COLUMN product_code DROP NOT NULL;
ALTER TABLE platform.page_default_value ALTER COLUMN product_code DROP NOT NULL;
ALTER TABLE platform.page_column_group ALTER COLUMN product_code DROP NOT NULL;
ALTER TABLE platform.page_column_group_field ALTER COLUMN product_code DROP NOT NULL;
ALTER TABLE platform.page_action ALTER COLUMN product_code DROP NOT NULL;
ALTER TABLE platform.page_pagination ALTER COLUMN product_code DROP NOT NULL;
ALTER TABLE platform.page_size_option ALTER COLUMN product_code DROP NOT NULL;

ALTER TABLE platform.page_definition
    ADD CONSTRAINT page_definition_pkey PRIMARY KEY (page_definition_id),
    ADD CONSTRAINT page_definition_context_key
        UNIQUE NULLS NOT DISTINCT (product_code, business_domain, page_kind);
ALTER TABLE platform.page_definition_field
    ADD CONSTRAINT page_definition_field_pkey PRIMARY KEY (page_definition_id, field_code),
    ADD CONSTRAINT page_definition_field_order_key UNIQUE (page_definition_id, sort_order),
    ADD CONSTRAINT page_definition_field_context_code_key
        UNIQUE NULLS NOT DISTINCT (product_code, business_domain, page_kind, field_code),
    ADD CONSTRAINT page_definition_field_context_order_key
        UNIQUE NULLS NOT DISTINCT (product_code, business_domain, page_kind, sort_order),
    ADD CONSTRAINT page_definition_field_definition_fk FOREIGN KEY (page_definition_id)
        REFERENCES platform.page_definition(page_definition_id) ON DELETE CASCADE;
ALTER TABLE platform.page_presentation
    ADD CONSTRAINT page_presentation_pkey PRIMARY KEY (page_presentation_id),
    ADD CONSTRAINT page_presentation_definition_key UNIQUE (page_definition_id),
    ADD CONSTRAINT page_presentation_context_key
        UNIQUE NULLS NOT DISTINCT (product_code, business_domain, page_kind),
    ADD CONSTRAINT page_presentation_definition_fk FOREIGN KEY (page_definition_id)
        REFERENCES platform.page_definition(page_definition_id) ON DELETE CASCADE;
ALTER TABLE platform.page_breadcrumb
    ADD CONSTRAINT page_breadcrumb_pkey PRIMARY KEY (page_presentation_id, code),
    ADD CONSTRAINT page_breadcrumb_order_key UNIQUE (page_presentation_id, sort_order),
    ADD CONSTRAINT page_breadcrumb_context_code_key
        UNIQUE NULLS NOT DISTINCT (product_code, business_domain, page_kind, code),
    ADD CONSTRAINT page_breadcrumb_context_order_key
        UNIQUE NULLS NOT DISTINCT (product_code, business_domain, page_kind, sort_order),
    ADD CONSTRAINT page_breadcrumb_presentation_fk FOREIGN KEY (page_presentation_id)
        REFERENCES platform.page_presentation(page_presentation_id) ON DELETE CASCADE;
ALTER TABLE platform.page_filter_definition
    ADD CONSTRAINT page_filter_definition_pkey PRIMARY KEY (page_presentation_id, code),
    ADD CONSTRAINT page_filter_definition_order_key UNIQUE (page_presentation_id, sort_order),
    ADD CONSTRAINT page_filter_definition_context_code_key
        UNIQUE NULLS NOT DISTINCT (product_code, business_domain, page_kind, code),
    ADD CONSTRAINT page_filter_definition_context_order_key
        UNIQUE NULLS NOT DISTINCT (product_code, business_domain, page_kind, sort_order),
    ADD CONSTRAINT page_filter_definition_presentation_fk FOREIGN KEY (page_presentation_id)
        REFERENCES platform.page_presentation(page_presentation_id) ON DELETE CASCADE;
ALTER TABLE platform.page_filter_option
    ADD CONSTRAINT page_filter_option_pkey PRIMARY KEY (page_presentation_id, filter_code, value),
    ADD CONSTRAINT page_filter_option_order_key UNIQUE (page_presentation_id, filter_code, sort_order),
    ADD CONSTRAINT page_filter_option_context_value_key
        UNIQUE NULLS NOT DISTINCT
            (product_code, business_domain, page_kind, filter_code, value),
    ADD CONSTRAINT page_filter_option_context_order_key
        UNIQUE NULLS NOT DISTINCT
            (product_code, business_domain, page_kind, filter_code, sort_order),
    ADD CONSTRAINT page_filter_option_filter_fk FOREIGN KEY (page_presentation_id, filter_code)
        REFERENCES platform.page_filter_definition(page_presentation_id, code) ON DELETE CASCADE;
ALTER TABLE platform.page_default_value
    ADD CONSTRAINT page_default_value_pkey PRIMARY KEY (page_presentation_id, filter_code),
    ADD CONSTRAINT page_default_value_context_key
        UNIQUE NULLS NOT DISTINCT (product_code, business_domain, page_kind, filter_code),
    ADD CONSTRAINT page_default_value_filter_fk FOREIGN KEY (page_presentation_id, filter_code)
        REFERENCES platform.page_filter_definition(page_presentation_id, code) ON DELETE CASCADE;
ALTER TABLE platform.page_column_group
    ADD CONSTRAINT page_column_group_pkey PRIMARY KEY (page_presentation_id, code),
    ADD CONSTRAINT page_column_group_order_key UNIQUE (page_presentation_id, sort_order),
    ADD CONSTRAINT page_column_group_context_code_key
        UNIQUE NULLS NOT DISTINCT (product_code, business_domain, page_kind, code),
    ADD CONSTRAINT page_column_group_context_order_key
        UNIQUE NULLS NOT DISTINCT (product_code, business_domain, page_kind, sort_order),
    ADD CONSTRAINT page_column_group_presentation_fk FOREIGN KEY (page_presentation_id)
        REFERENCES platform.page_presentation(page_presentation_id) ON DELETE CASCADE;
ALTER TABLE platform.page_column_group_field
    ADD CONSTRAINT page_column_group_field_pkey
        PRIMARY KEY (page_presentation_id, group_code, field_code),
    ADD CONSTRAINT page_column_group_field_order_key
        UNIQUE (page_presentation_id, group_code, sort_order),
    ADD CONSTRAINT page_column_group_field_page_field_key
        UNIQUE (page_presentation_id, field_code),
    ADD CONSTRAINT page_column_group_field_context_field_key
        UNIQUE NULLS NOT DISTINCT
            (product_code, business_domain, page_kind, group_code, field_code),
    ADD CONSTRAINT page_column_group_field_context_order_key
        UNIQUE NULLS NOT DISTINCT
            (product_code, business_domain, page_kind, group_code, sort_order),
    ADD CONSTRAINT page_column_group_field_context_page_field_key
        UNIQUE NULLS NOT DISTINCT
            (product_code, business_domain, page_kind, field_code),
    ADD CONSTRAINT page_column_group_field_group_fk
        FOREIGN KEY (page_presentation_id, group_code)
        REFERENCES platform.page_column_group(page_presentation_id, code) ON DELETE CASCADE,
    ADD CONSTRAINT page_column_group_field_definition_field_fk
        FOREIGN KEY (page_definition_id, field_code)
        REFERENCES platform.page_definition_field(page_definition_id, field_code) ON DELETE CASCADE;
ALTER TABLE platform.page_action
    ADD CONSTRAINT page_action_pkey PRIMARY KEY (page_presentation_id, code),
    ADD CONSTRAINT page_action_order_key UNIQUE (page_presentation_id, sort_order),
    ADD CONSTRAINT page_action_context_code_key
        UNIQUE NULLS NOT DISTINCT (product_code, business_domain, page_kind, code),
    ADD CONSTRAINT page_action_context_order_key
        UNIQUE NULLS NOT DISTINCT (product_code, business_domain, page_kind, sort_order),
    ADD CONSTRAINT page_action_presentation_fk FOREIGN KEY (page_presentation_id)
        REFERENCES platform.page_presentation(page_presentation_id) ON DELETE CASCADE;
ALTER TABLE platform.page_pagination
    ADD CONSTRAINT page_pagination_pkey PRIMARY KEY (page_presentation_id),
    ADD CONSTRAINT page_pagination_context_key
        UNIQUE NULLS NOT DISTINCT (product_code, business_domain, page_kind),
    ADD CONSTRAINT page_pagination_presentation_fk FOREIGN KEY (page_presentation_id)
        REFERENCES platform.page_presentation(page_presentation_id) ON DELETE CASCADE;
ALTER TABLE platform.page_size_option
    ADD CONSTRAINT page_size_option_pkey PRIMARY KEY (page_presentation_id, page_size),
    ADD CONSTRAINT page_size_option_order_key UNIQUE (page_presentation_id, sort_order),
    ADD CONSTRAINT page_size_option_context_size_key
        UNIQUE NULLS NOT DISTINCT (product_code, business_domain, page_kind, page_size),
    ADD CONSTRAINT page_size_option_context_order_key
        UNIQUE NULLS NOT DISTINCT (product_code, business_domain, page_kind, sort_order),
    ADD CONSTRAINT page_size_option_pagination_fk FOREIGN KEY (page_presentation_id)
        REFERENCES platform.page_pagination(page_presentation_id) ON DELETE CASCADE
        DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE platform.page_pagination
    ADD CONSTRAINT page_pagination_default_size_fk
        FOREIGN KEY (page_presentation_id, default_page_size)
        REFERENCES platform.page_size_option(page_presentation_id, page_size)
        DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE platform.page_default_context
    ADD CONSTRAINT page_default_product_definition_fk
        FOREIGN KEY (default_product_code, business_domain, page_kind)
        REFERENCES platform.page_definition(product_code, business_domain, page_kind);
ALTER TABLE market.market_record_projection
    ADD CONSTRAINT market_record_projection_presentation_fk FOREIGN KEY (page_presentation_id)
        REFERENCES platform.page_presentation(page_presentation_id);

CREATE FUNCTION platform.resolve_page_definition_identity()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    SELECT definition.page_definition_id INTO NEW.page_definition_id
    FROM platform.page_definition definition
    WHERE definition.product_code IS NOT DISTINCT FROM NEW.product_code
      AND definition.business_domain = NEW.business_domain
      AND definition.page_kind = NEW.page_kind;
    IF NEW.page_definition_id IS NULL THEN
        RAISE EXCEPTION 'Page definition context does not exist';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION platform.resolve_page_presentation_identity()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    SELECT presentation.page_presentation_id INTO NEW.page_presentation_id
    FROM platform.page_presentation presentation
    WHERE presentation.product_code IS NOT DISTINCT FROM NEW.product_code
      AND presentation.business_domain = NEW.business_domain
      AND presentation.page_kind = NEW.page_kind;
    IF NEW.page_presentation_id IS NULL THEN
        RAISE EXCEPTION 'Page presentation context does not exist';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER page_presentation_resolves_definition
BEFORE INSERT OR UPDATE ON platform.page_presentation
FOR EACH ROW EXECUTE FUNCTION platform.resolve_page_definition_identity();
CREATE TRIGGER page_definition_field_resolves_definition
BEFORE INSERT OR UPDATE ON platform.page_definition_field
FOR EACH ROW EXECUTE FUNCTION platform.resolve_page_definition_identity();
CREATE TRIGGER page_column_group_field_resolves_definition
BEFORE INSERT OR UPDATE ON platform.page_column_group_field
FOR EACH ROW EXECUTE FUNCTION platform.resolve_page_definition_identity();

CREATE TRIGGER page_breadcrumb_resolves_presentation
BEFORE INSERT OR UPDATE ON platform.page_breadcrumb
FOR EACH ROW EXECUTE FUNCTION platform.resolve_page_presentation_identity();
CREATE TRIGGER page_filter_definition_resolves_presentation
BEFORE INSERT OR UPDATE ON platform.page_filter_definition
FOR EACH ROW EXECUTE FUNCTION platform.resolve_page_presentation_identity();
CREATE TRIGGER page_filter_option_resolves_presentation
BEFORE INSERT OR UPDATE ON platform.page_filter_option
FOR EACH ROW EXECUTE FUNCTION platform.resolve_page_presentation_identity();
CREATE TRIGGER page_default_value_resolves_presentation
BEFORE INSERT OR UPDATE ON platform.page_default_value
FOR EACH ROW EXECUTE FUNCTION platform.resolve_page_presentation_identity();
CREATE TRIGGER page_column_group_resolves_presentation
BEFORE INSERT OR UPDATE ON platform.page_column_group
FOR EACH ROW EXECUTE FUNCTION platform.resolve_page_presentation_identity();
CREATE TRIGGER page_column_group_field_resolves_presentation
BEFORE INSERT OR UPDATE ON platform.page_column_group_field
FOR EACH ROW EXECUTE FUNCTION platform.resolve_page_presentation_identity();
CREATE TRIGGER page_action_resolves_presentation
BEFORE INSERT OR UPDATE ON platform.page_action
FOR EACH ROW EXECUTE FUNCTION platform.resolve_page_presentation_identity();
CREATE TRIGGER page_pagination_resolves_presentation
BEFORE INSERT OR UPDATE ON platform.page_pagination
FOR EACH ROW EXECUTE FUNCTION platform.resolve_page_presentation_identity();
CREATE TRIGGER page_size_option_resolves_presentation
BEFORE INSERT OR UPDATE ON platform.page_size_option
FOR EACH ROW EXECUTE FUNCTION platform.resolve_page_presentation_identity();
CREATE TRIGGER market_record_projection_resolves_presentation
BEFORE INSERT OR UPDATE ON market.market_record_projection
FOR EACH ROW EXECUTE FUNCTION platform.resolve_page_presentation_identity();

CREATE OR REPLACE FUNCTION platform.assert_page_has_pagination(
    checked_product_code varchar,
    checked_business_domain varchar,
    checked_page_kind varchar)
RETURNS void
LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM platform.page_presentation presentation
        WHERE presentation.product_code IS NOT DISTINCT FROM checked_product_code
          AND presentation.business_domain = checked_business_domain
          AND presentation.page_kind = checked_page_kind
    ) AND NOT EXISTS (
        SELECT 1 FROM platform.page_pagination pagination
        WHERE pagination.product_code IS NOT DISTINCT FROM checked_product_code
          AND pagination.business_domain = checked_business_domain
          AND pagination.page_kind = checked_page_kind
    ) THEN
        RAISE EXCEPTION 'Every page presentation requires pagination configuration';
    END IF;
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

COMMENT ON COLUMN platform.page_definition.product_code IS
    'Optional product dimension. NULL represents a genuinely product-independent page, never a sentinel product.';
