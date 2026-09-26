CREATE TABLE market_intelligence.fao_food_price_index (
    series_code varchar(24) NOT NULL,
    period date NOT NULL,
    value numeric(12,3) NOT NULL CHECK (value > 0),
    source_url text NOT NULL,
    fetched_at timestamptz NOT NULL,
    source_sha256 char(64) NOT NULL,
    PRIMARY KEY (series_code, period)
);

CREATE TABLE market_intelligence.fao_food_price_revision (
    revision_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    series_code varchar(24) NOT NULL,
    period date NOT NULL,
    previous_value numeric(12,3) NOT NULL,
    revised_value numeric(12,3) NOT NULL,
    detected_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE FUNCTION market_intelligence.record_fao_food_price_revision()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.value IS DISTINCT FROM NEW.value THEN
        INSERT INTO market_intelligence.fao_food_price_revision
            (series_code, period, previous_value, revised_value)
        VALUES (OLD.series_code, OLD.period, OLD.value, NEW.value);
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER fao_food_price_revision_trigger
AFTER UPDATE ON market_intelligence.fao_food_price_index
FOR EACH ROW EXECUTE FUNCTION market_intelligence.record_fao_food_price_revision();

GRANT SELECT, INSERT, UPDATE ON market_intelligence.fao_food_price_index
    TO qiqihar_enterprise_runtime;
GRANT SELECT, INSERT ON market_intelligence.fao_food_price_revision
    TO qiqihar_enterprise_runtime;
GRANT USAGE ON SEQUENCE market_intelligence.fao_food_price_revision_revision_id_seq
    TO qiqihar_enterprise_runtime;
