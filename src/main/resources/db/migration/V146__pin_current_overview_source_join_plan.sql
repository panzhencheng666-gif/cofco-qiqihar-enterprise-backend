-- Keep the cached current-overview source plan on the measured hash/merge path
-- without requiring an extra SET LOCAL round trip for every API request.
ALTER FUNCTION overview.current_sample_point_query_source(
  integer,varchar,boolean,boolean) SET enable_nestloop=off;
