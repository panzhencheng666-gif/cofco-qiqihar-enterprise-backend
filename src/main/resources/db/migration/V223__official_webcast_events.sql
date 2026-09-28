CREATE TABLE market_intelligence.webcast_event (
    source_code varchar(40) NOT NULL,
    event_url text NOT NULL,
    title text NOT NULL,
    starts_at timestamptz NOT NULL,
    fetched_at timestamptz NOT NULL,
    PRIMARY KEY (source_code, event_url)
);

CREATE INDEX webcast_event_starts_at
    ON market_intelligence.webcast_event(starts_at DESC);

GRANT SELECT,INSERT,UPDATE ON market_intelligence.webcast_event TO qiqihar_enterprise_runtime;
GRANT SELECT,INSERT,UPDATE ON market_intelligence.webcast_event TO CURRENT_USER;
