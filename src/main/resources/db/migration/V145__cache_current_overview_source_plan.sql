-- Keep the exact current overview eligibility contract while letting PostgreSQL
-- cache the expensive layered-view plan once per runtime session.
CREATE FUNCTION overview.current_sample_point_query_source(
    p_year integer,
    p_product varchar,
    p_all_products boolean,
    p_include_period_history boolean)
RETURNS SETOF overview.sample_point_query_source
LANGUAGE plpgsql
STABLE
SECURITY INVOKER
SET search_path = pg_catalog,overview,production,market,logistics
AS $$
BEGIN
  RETURN QUERY
  SELECT source.*
  FROM overview.sample_point_query_source source
  WHERE (p_all_products OR source.product_code=p_product)
    AND (
      source.category_code='PRODUCTION' AND EXISTS(
        SELECT 1 FROM production.production_record record
        WHERE record.record_id=source.source_record_id
          AND record.survey_year=p_year
          AND record.survey_period_governance_state='CONFIRMED'
          AND (p_include_period_history OR EXISTS(
            SELECT 1 FROM production.effective_approved_production_record effective
            WHERE effective.record_id=record.record_id)))
      OR source.category_code='MARKET' AND EXISTS(
        SELECT 1 FROM market.market_record record
        WHERE record.record_id=source.source_record_id
          AND record.survey_year=p_year
          AND record.survey_period_governance_state='CONFIRMED'
          AND (p_include_period_history OR EXISTS(
            SELECT 1 FROM market.effective_approved_market_record effective
            WHERE effective.record_id=record.record_id)))
      OR source.category_code='LOGISTICS' AND EXISTS(
        SELECT 1 FROM logistics.route_event event
        WHERE event.event_id::text=source.source_record_id
          AND event.survey_year=p_year
          AND event.survey_period_governance_state='CONFIRMED')
    );
END;
$$;

REVOKE ALL ON FUNCTION overview.current_sample_point_query_source(
  integer,varchar,boolean,boolean) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION overview.current_sample_point_query_source(
  integer,varchar,boolean,boolean) TO qiqihar_enterprise_runtime;

COMMENT ON FUNCTION overview.current_sample_point_query_source(
  integer,varchar,boolean,boolean) IS
  'Approved and confirmed current overview sources with a reusable session-local query plan.';
