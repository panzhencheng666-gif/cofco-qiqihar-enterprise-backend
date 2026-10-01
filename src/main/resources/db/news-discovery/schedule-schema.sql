-- Explicit local candidate schema; requires reviewed news-only migration before production use.
CREATE TABLE market_intelligence.news_discovery_host_schedule (
    source_host text PRIMARY KEY,
    next_attempt_at timestamptz NOT NULL DEFAULT '-infinity',
    lease_token uuid,
    lease_until timestamptz NOT NULL DEFAULT '-infinity',
    failures integer NOT NULL DEFAULT 0 CHECK (failures BETWEEN 0 AND 30),
    crawl_delay_millis bigint NOT NULL DEFAULT 0 CHECK (crawl_delay_millis >= 0)
);
