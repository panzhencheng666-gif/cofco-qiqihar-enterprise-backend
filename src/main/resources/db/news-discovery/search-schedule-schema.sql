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
