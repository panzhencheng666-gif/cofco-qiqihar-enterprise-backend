CREATE FUNCTION platform.reject_page_identity_change()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    identity_column text;
BEGIN
    FOREACH identity_column IN ARRAY TG_ARGV LOOP
        IF to_jsonb(NEW) -> identity_column IS DISTINCT FROM to_jsonb(OLD) -> identity_column THEN
            RAISE EXCEPTION 'Page identity and context are immutable: %.%', TG_TABLE_NAME, identity_column;
        END IF;
    END LOOP;
    RETURN NEW;
END;
$$;

CREATE TRIGGER page_definition_identity_is_immutable
BEFORE UPDATE ON platform.page_definition
FOR EACH ROW EXECUTE FUNCTION platform.reject_page_identity_change(
    'page_definition_id', 'product_code', 'business_domain', 'page_kind');

CREATE TRIGGER page_presentation_identity_is_immutable
BEFORE UPDATE ON platform.page_presentation
FOR EACH ROW EXECUTE FUNCTION platform.reject_page_identity_change(
    'page_presentation_id', 'page_definition_id', 'product_code', 'business_domain', 'page_kind');

CREATE TRIGGER page_definition_field_identity_is_immutable
BEFORE UPDATE ON platform.page_definition_field
FOR EACH ROW EXECUTE FUNCTION platform.reject_page_identity_change(
    'page_definition_id', 'product_code', 'business_domain', 'page_kind');

CREATE TRIGGER page_breadcrumb_identity_is_immutable
BEFORE UPDATE ON platform.page_breadcrumb
FOR EACH ROW EXECUTE FUNCTION platform.reject_page_identity_change(
    'page_presentation_id', 'product_code', 'business_domain', 'page_kind');

CREATE TRIGGER page_filter_definition_identity_is_immutable
BEFORE UPDATE ON platform.page_filter_definition
FOR EACH ROW EXECUTE FUNCTION platform.reject_page_identity_change(
    'page_presentation_id', 'product_code', 'business_domain', 'page_kind');

CREATE TRIGGER page_filter_option_identity_is_immutable
BEFORE UPDATE ON platform.page_filter_option
FOR EACH ROW EXECUTE FUNCTION platform.reject_page_identity_change(
    'page_presentation_id', 'product_code', 'business_domain', 'page_kind');

CREATE TRIGGER page_default_value_identity_is_immutable
BEFORE UPDATE ON platform.page_default_value
FOR EACH ROW EXECUTE FUNCTION platform.reject_page_identity_change(
    'page_presentation_id', 'product_code', 'business_domain', 'page_kind');

CREATE TRIGGER page_column_group_identity_is_immutable
BEFORE UPDATE ON platform.page_column_group
FOR EACH ROW EXECUTE FUNCTION platform.reject_page_identity_change(
    'page_presentation_id', 'product_code', 'business_domain', 'page_kind');

CREATE TRIGGER page_column_group_field_identity_is_immutable
BEFORE UPDATE ON platform.page_column_group_field
FOR EACH ROW EXECUTE FUNCTION platform.reject_page_identity_change(
    'page_presentation_id', 'page_definition_id', 'product_code', 'business_domain', 'page_kind');

CREATE TRIGGER page_action_identity_is_immutable
BEFORE UPDATE ON platform.page_action
FOR EACH ROW EXECUTE FUNCTION platform.reject_page_identity_change(
    'page_presentation_id', 'product_code', 'business_domain', 'page_kind');

CREATE TRIGGER page_pagination_identity_is_immutable
BEFORE UPDATE ON platform.page_pagination
FOR EACH ROW EXECUTE FUNCTION platform.reject_page_identity_change(
    'page_presentation_id', 'product_code', 'business_domain', 'page_kind');

CREATE TRIGGER page_size_option_identity_is_immutable
BEFORE UPDATE ON platform.page_size_option
FOR EACH ROW EXECUTE FUNCTION platform.reject_page_identity_change(
    'page_presentation_id', 'product_code', 'business_domain', 'page_kind');

CREATE TRIGGER market_record_projection_page_identity_is_immutable
BEFORE UPDATE ON market.market_record_projection
FOR EACH ROW EXECUTE FUNCTION platform.reject_page_identity_change(
    'page_presentation_id', 'product_code', 'business_domain', 'page_kind');

COMMENT ON FUNCTION platform.reject_page_identity_change() IS
    'Task 4 forward-only consistency guard: surrogate page identity and retained legacy context cannot diverge after creation.';
