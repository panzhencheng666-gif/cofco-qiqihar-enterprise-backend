ALTER TABLE platform.object_type
    ADD COLUMN overview_enabled boolean NOT NULL DEFAULT false,
    ADD COLUMN overview_icon_key varchar(80);

UPDATE platform.object_type
SET overview_enabled = true,
    overview_icon_key = CASE code
        WHEN 'FARMER' THEN 'farmer'
        WHEN 'VILLAGE_COMMITTEE' THEN 'village-committee'
        WHEN 'AGRICULTURAL_TECH_STATION' THEN 'agricultural-tech-station'
        WHEN 'TRADER' THEN 'trader'
        WHEN 'DEEP_PROCESSOR' THEN 'deep-processor'
        WHEN 'WHOLESALE_MARKET' THEN 'wholesale-market'
        WHEN 'RESERVE_ENTERPRISE' THEN 'reserve-enterprise'
        WHEN 'BREEDING_FACTORY' THEN 'breeding-factory'
        WHEN 'FEED_MILL' THEN 'feed-mill'
    END
WHERE code IN (
    'FARMER','VILLAGE_COMMITTEE','AGRICULTURAL_TECH_STATION',
    'TRADER','DEEP_PROCESSOR','WHOLESALE_MARKET','RESERVE_ENTERPRISE',
    'BREEDING_FACTORY','FEED_MILL'
);

UPDATE platform.object_type
SET name = CASE code
    WHEN 'DEEP_PROCESSOR' THEN '深加工企业'
    WHEN 'BREEDING_FACTORY' THEN '养殖场'
    ELSE name
END
WHERE code IN ('DEEP_PROCESSOR','BREEDING_FACTORY');

ALTER TABLE platform.object_type
    ADD CONSTRAINT overview_object_type_icon_contract CHECK (
        (overview_enabled AND overview_icon_key IS NOT NULL AND btrim(overview_icon_key) <> '')
        OR (NOT overview_enabled AND overview_icon_key IS NULL)
    );

CREATE UNIQUE INDEX overview_object_type_icon_key_unique
    ON platform.object_type(overview_icon_key)
    WHERE overview_enabled;

ALTER TABLE registry.sample_point
    ADD COLUMN coordinate_shared_verified boolean NOT NULL DEFAULT false;

CREATE TABLE registry.sample_point_subject_identity (
    business_domain varchar(30) NOT NULL,
    subject_id varchar(160) NOT NULL,
    sample_point_id uuid NOT NULL REFERENCES registry.sample_point(sample_point_id),
    created_at timestamptz NOT NULL,
    created_by varchar(120) NOT NULL REFERENCES platform.security_user(subject_id),
    PRIMARY KEY (business_domain, subject_id),
    UNIQUE (business_domain, sample_point_id),
    CHECK (business_domain IN ('PRODUCTION','MARKET')),
    CHECK (btrim(subject_id) <> '')
);

COMMENT ON COLUMN platform.object_type.overview_enabled IS
    'Authoritative opt-in for overview sample-point types; product applicability never changes this matrix.';
COMMENT ON COLUMN platform.object_type.overview_icon_key IS
    'Stable semantic SVG key for one retained overview business type.';
COMMENT ON COLUMN registry.sample_point.coordinate_shared_verified IS
    'True only after distinct subjects sharing one exact governed coordinate have been verified.';
COMMENT ON TABLE registry.sample_point_subject_identity IS
    'Stable non-name subject identity used to reuse one governed sample point across products and records.';
