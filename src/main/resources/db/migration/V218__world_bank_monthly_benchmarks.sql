CREATE SCHEMA market_intelligence;

CREATE TABLE market_intelligence.monthly_benchmark_price (
    series_code varchar(32) NOT NULL,
    period date NOT NULL,
    price numeric(20,6) NOT NULL CHECK (price > 0),
    unit varchar(32) NOT NULL,
    source_url text NOT NULL,
    source_updated_on date NOT NULL,
    fetched_at timestamptz NOT NULL,
    source_sha256 char(64) NOT NULL,
    PRIMARY KEY (series_code, period)
);

CREATE TABLE market_intelligence.source_sync_state (
    source_code varchar(40) PRIMARY KEY,
    last_attempt_at timestamptz,
    last_success_at timestamptz,
    latest_period date,
    last_error varchar(400)
);

CREATE TABLE market_intelligence.monthly_benchmark_revision (
    revision_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    series_code varchar(32) NOT NULL,
    period date NOT NULL,
    previous_price numeric(20,6) NOT NULL,
    revised_price numeric(20,6) NOT NULL,
    previous_source_sha256 char(64) NOT NULL,
    revised_source_sha256 char(64) NOT NULL,
    detected_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX monthly_benchmark_revision_series_period
    ON market_intelligence.monthly_benchmark_revision(series_code,period,detected_at DESC);

CREATE FUNCTION market_intelligence.record_monthly_benchmark_revision()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.price IS DISTINCT FROM NEW.price THEN
        INSERT INTO market_intelligence.monthly_benchmark_revision
            (series_code,period,previous_price,revised_price,previous_source_sha256,revised_source_sha256)
        VALUES (OLD.series_code,OLD.period,OLD.price,NEW.price,OLD.source_sha256,NEW.source_sha256);
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER monthly_benchmark_revision_trigger
AFTER UPDATE ON market_intelligence.monthly_benchmark_price
FOR EACH ROW EXECUTE FUNCTION market_intelligence.record_monthly_benchmark_revision();

GRANT USAGE ON SCHEMA market_intelligence TO qiqihar_enterprise_runtime;
GRANT SELECT,INSERT,UPDATE ON market_intelligence.monthly_benchmark_price,
    market_intelligence.source_sync_state TO qiqihar_enterprise_runtime;
GRANT SELECT,INSERT ON market_intelligence.monthly_benchmark_revision TO qiqihar_enterprise_runtime;
GRANT USAGE ON SEQUENCE market_intelligence.monthly_benchmark_revision_revision_id_seq
    TO qiqihar_enterprise_runtime;
GRANT USAGE ON SCHEMA market_intelligence TO CURRENT_USER;
GRANT SELECT,INSERT,UPDATE ON market_intelligence.monthly_benchmark_price,
    market_intelligence.source_sync_state TO CURRENT_USER;
GRANT SELECT,INSERT ON market_intelligence.monthly_benchmark_revision TO CURRENT_USER;
GRANT USAGE ON SEQUENCE market_intelligence.monthly_benchmark_revision_revision_id_seq TO CURRENT_USER;
