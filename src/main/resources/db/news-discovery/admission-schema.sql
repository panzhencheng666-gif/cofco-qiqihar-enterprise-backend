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
