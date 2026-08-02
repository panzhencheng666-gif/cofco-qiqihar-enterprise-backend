CREATE TABLE market.market_record_projection (
    record_id varchar(80) PRIMARY KEY,
    product_code varchar(40) NOT NULL,
    business_domain varchar(30) NOT NULL CHECK (business_domain = 'MARKET'),
    page_kind varchar(40) NOT NULL,
    observed_at timestamptz NOT NULL,
    values jsonb NOT NULL CHECK (jsonb_typeof(values) = 'object'),
    FOREIGN KEY (product_code, business_domain, page_kind)
        REFERENCES platform.page_presentation(product_code, business_domain, page_kind)
);

CREATE INDEX market_record_projection_page_order
    ON market.market_record_projection
        (product_code, business_domain, page_kind, observed_at, record_id);

COMMENT ON TABLE market.market_record_projection IS
    'Read-only market query projection. Production migrations intentionally contain no business record seed.';
