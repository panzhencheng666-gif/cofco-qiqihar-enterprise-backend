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
