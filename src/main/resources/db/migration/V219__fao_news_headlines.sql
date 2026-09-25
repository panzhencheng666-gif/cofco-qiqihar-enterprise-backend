CREATE TABLE market_intelligence.news_headline (
    source_code varchar(40) NOT NULL,
    article_url text NOT NULL,
    title text NOT NULL,
    published_at timestamptz NOT NULL,
    fetched_at timestamptz NOT NULL,
    PRIMARY KEY (source_code, article_url)
);

CREATE INDEX news_headline_published_at
    ON market_intelligence.news_headline(published_at DESC);

GRANT SELECT,INSERT,UPDATE ON market_intelligence.news_headline TO qiqihar_enterprise_runtime;
GRANT SELECT,INSERT,UPDATE ON market_intelligence.news_headline TO CURRENT_USER;
