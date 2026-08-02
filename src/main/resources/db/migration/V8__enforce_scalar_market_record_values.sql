CREATE FUNCTION market.market_record_values_are_scalar(candidate jsonb)
RETURNS boolean
LANGUAGE sql
IMMUTABLE
STRICT
AS $$
    SELECT jsonb_typeof(candidate) = 'object'
       AND NOT EXISTS (
            SELECT 1
            FROM jsonb_each(candidate) AS cell
            WHERE jsonb_typeof(cell.value) NOT IN ('string', 'number', 'null'));
$$;

ALTER TABLE market.market_record_projection
    ADD CONSTRAINT market_record_projection_scalar_values
    CHECK (market.market_record_values_are_scalar(values));

COMMENT ON CONSTRAINT market_record_projection_scalar_values
    ON market.market_record_projection IS
    'Table cell projection values are restricted to string, number, or null.';
