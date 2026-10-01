-- News-only candidate. Explicit reviewed runner only; never shared Flyway locations.
-- No source authorization seeds. Runtime cannot approve its own acquisition or embeds.
CREATE TABLE market_intelligence.webcast_source_admission (
    source_code varchar(40) PRIMARY KEY CHECK (source_code = 'un-webtv'),
    provider varchar(40) NOT NULL CHECK (provider = 'un-webtv'),
    fetch_allowed boolean NOT NULL DEFAULT false,
    metadata_display_allowed boolean NOT NULL DEFAULT false,
    embed_allowed boolean NOT NULL DEFAULT false,
    evidence text NOT NULL CHECK (length(trim(evidence)) BETWEEN 1 AND 2000),
    verified_at timestamptz NOT NULL,
    valid_until timestamptz NOT NULL CHECK (valid_until > verified_at)
);
CREATE TABLE market_intelligence.webcast_programme_state (
    source_code varchar(40) NOT NULL CHECK (source_code = 'un-webtv'),
    event_url text NOT NULL,
    calendar_uid varchar(40) NOT NULL,
    programme_url text,
    provider varchar(40),
    entry_id varchar(20),
    ends_at timestamptz NOT NULL,
    source_modified_at timestamptz,
    source_status varchar(20) NOT NULL CHECK (source_status IN ('LIVE','UNKNOWN','CANCELLED')),
    observed_at timestamptz NOT NULL,
    snapshot_id uuid NOT NULL,
    expires_at timestamptz NOT NULL,
    PRIMARY KEY(source_code,event_url),
    FOREIGN KEY(source_code,event_url) REFERENCES market_intelligence.webcast_event(source_code,event_url),
    UNIQUE(source_code,calendar_uid),
    CHECK ((programme_url IS NULL AND provider IS NULL AND entry_id IS NULL)
        OR (programme_url IS NOT NULL AND provider IS NOT NULL AND entry_id IS NOT NULL
            AND provider = 'un-webtv' AND entry_id ~ '^1_[a-z0-9]{8}$'
            AND programme_url = 'https://webtv.un.org/en/asset/k1' || substring(entry_id from 3 for 1) || '/k1' || substring(entry_id from 3))),
    CHECK (source_status <> 'LIVE' OR entry_id IS NOT NULL)
);
CREATE INDEX webcast_programme_snapshot ON market_intelligence.webcast_programme_state(snapshot_id);
REVOKE ALL ON market_intelligence.webcast_source_admission,
    market_intelligence.webcast_programme_state FROM PUBLIC,qiqihar_enterprise_runtime;
GRANT SELECT ON market_intelligence.webcast_source_admission TO qiqihar_enterprise_runtime;
GRANT SELECT,INSERT,UPDATE ON market_intelligence.webcast_programme_state TO qiqihar_enterprise_runtime;
