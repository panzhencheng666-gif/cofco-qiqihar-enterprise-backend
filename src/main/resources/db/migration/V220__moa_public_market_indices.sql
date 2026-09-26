CREATE TABLE market_intelligence.china_daily_index (
    series_code varchar(48) NOT NULL,
    period date NOT NULL,
    value numeric(18,4) NOT NULL CHECK (value > 0),
    source_url text NOT NULL,
    fetched_at timestamptz NOT NULL,
    PRIMARY KEY (series_code, period)
);

CREATE INDEX china_daily_index_recent ON market_intelligence.china_daily_index(period DESC);

GRANT SELECT,INSERT,UPDATE ON market_intelligence.china_daily_index TO qiqihar_enterprise_runtime;
GRANT SELECT,INSERT,UPDATE ON market_intelligence.china_daily_index TO CURRENT_USER;
