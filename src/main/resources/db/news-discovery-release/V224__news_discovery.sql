-- NEWS-ONLY incremental candidate. Never add this location to shared application Flyway.
-- Execute only with the existing news history, owner role, fresh backup and reviewed plan.

-- Explicit news-only deployment prerequisite; NOT an automatically scanned Flyway migration.
-- Production application requires separate reviewed migration and runtime grants.
CREATE TABLE market_intelligence.news_discovery_candidate (
    article_url text PRIMARY KEY,
    source_host text NOT NULL,
    title text NOT NULL,
    search_claimed_date text NOT NULL,
    first_discovered_at timestamptz NOT NULL,
    last_discovered_at timestamptz NOT NULL,
    engine text NOT NULL CHECK (engine IN ('CNLiteBasic', 'GlobalAdvanced')),
    query_text text NOT NULL,
    review_state text NOT NULL DEFAULT 'PENDING_VERIFICATION'
        CHECK (review_state IN ('PENDING_VERIFICATION', 'VERIFIED', 'REJECTED')),
    review_reason text NOT NULL DEFAULT 'Source time, topic and collection policy not verified',
    review_evidence jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(review_evidence) = 'object'),
    reviewed_at timestamptz,
    next_attempt_at timestamptz NOT NULL DEFAULT '-infinity',
    CHECK (last_discovered_at >= first_discovered_at)
);
CREATE INDEX news_discovery_pending
    ON market_intelligence.news_discovery_candidate(review_state, last_discovered_at DESC);
CREATE INDEX news_discovery_due
    ON market_intelligence.news_discovery_candidate(source_host,next_attempt_at)
    WHERE review_state='PENDING_VERIFICATION';

-- No seed grants. Reviewed administrators alone may write; runtime receives SELECT only.
-- This is NOT an automatic Flyway migration or a declaration that any source is authorized.
CREATE TABLE market_intelligence.news_discovery_source_admission (
    origin_uri text PRIMARY KEY,
    review_reference text NOT NULL CHECK (length(trim(review_reference)) BETWEEN 1 AND 500),
    checked_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL CHECK (expires_at > checked_at),
    fetch_allowed boolean NOT NULL DEFAULT false,
    metadata_display_allowed boolean NOT NULL DEFAULT false
);

-- Explicit local candidate schema; requires reviewed news-only migration before production use.
CREATE TABLE market_intelligence.news_discovery_host_schedule (
    source_host text PRIMARY KEY,
    next_attempt_at timestamptz NOT NULL DEFAULT '-infinity',
    lease_token uuid,
    lease_until timestamptz NOT NULL DEFAULT '-infinity',
    failures integer NOT NULL DEFAULT 0 CHECK (failures BETWEEN 0 AND 30),
    crawl_delay_millis bigint NOT NULL DEFAULT 0 CHECK (crawl_delay_millis >= 0)
);

-- Explicit news-only migration prerequisite, not automatically applied at startup.
CREATE TABLE market_intelligence.news_discovery_search_schedule (
    slot integer PRIMARY KEY CHECK (slot=1),
    next_engine text NOT NULL CHECK (next_engine IN ('CNLiteBasic','GlobalAdvanced')),
    next_search_at timestamptz NOT NULL DEFAULT '-infinity',
    attempt_token uuid,
    last_started_at timestamptz,
    last_completed_at timestamptz,
    last_state text NOT NULL DEFAULT 'NOT_RUN',
    last_reason text NOT NULL DEFAULT 'Not enabled',
    failures integer NOT NULL DEFAULT 0 CHECK (failures BETWEEN 0 AND 30)
);
INSERT INTO market_intelligence.news_discovery_search_schedule(slot,next_engine) VALUES (1,'CNLiteBasic');

-- Reset table ACLs inherited from default privileges for PUBLIC and the runtime group.
-- Do not change schema-wide/default privileges or any pre-existing news/risk object.
REVOKE ALL ON market_intelligence.news_discovery_candidate,
    market_intelligence.news_discovery_source_admission,
    market_intelligence.news_discovery_host_schedule,
    market_intelligence.news_discovery_search_schedule FROM PUBLIC, qiqihar_enterprise_runtime;
GRANT SELECT, INSERT, UPDATE ON market_intelligence.news_discovery_candidate,
    market_intelligence.news_discovery_host_schedule,
    market_intelligence.news_discovery_search_schedule TO qiqihar_enterprise_runtime;
GRANT SELECT ON market_intelligence.news_discovery_source_admission TO qiqihar_enterprise_runtime;
