CREATE TABLE overview.storage_facility (
    facility_code varchar(60) PRIMARY KEY,
    facility_name varchar(200) NOT NULL,
    work_unit_code varchar(60),
    relation_type varchar(30) NOT NULL
        CHECK (relation_type IN ('OWNED','LEASED','HISTORICAL_LEASED')),
    region_code varchar(12) NOT NULL REFERENCES platform.region(code),
    address text,
    geometry geometry(Point,4326),
    coordinate_precision varchar(20) NOT NULL DEFAULT 'UNKNOWN'
        CHECK (coordinate_precision IN ('EXACT','STREET','TOWN','UNKNOWN')),
    operational_status varchar(20) NOT NULL DEFAULT 'UNKNOWN'
        CHECK (operational_status IN ('ACTIVE','INACTIVE','UNKNOWN')),
    capacity_tonnes numeric(18,3) CHECK (capacity_tonnes IS NULL OR capacity_tonnes >= 0),
    capacity_as_of date,
    valid_from date,
    valid_to date,
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((geometry IS NULL AND coordinate_precision='UNKNOWN') OR geometry IS NOT NULL),
    CHECK (geometry IS NULL OR (ST_IsValid(geometry) AND NOT ST_IsEmpty(geometry) AND ST_SRID(geometry)=4326)),
    CHECK (valid_to IS NULL OR valid_from IS NULL OR valid_to >= valid_from)
);

CREATE INDEX storage_facility_geometry_gix ON overview.storage_facility USING gist(geometry);
CREATE INDEX storage_facility_region_idx ON overview.storage_facility(region_code,relation_type);

CREATE TABLE overview.storage_facility_price (
    price_id uuid PRIMARY KEY,
    facility_code varchar(60) NOT NULL REFERENCES overview.storage_facility(facility_code) ON DELETE CASCADE,
    product_code varchar(40) REFERENCES platform.product(code),
    quality_requirement text,
    price_value numeric(18,4) NOT NULL CHECK (price_value >= 0),
    price_unit varchar(30) NOT NULL,
    effective_on date NOT NULL,
    expires_on date,
    source_url text NOT NULL,
    source_name varchar(200) NOT NULL,
    source_classification varchar(20) NOT NULL
        CHECK (source_classification IN ('INTERNAL_GOVERNED','PUBLIC')),
    captured_at timestamptz NOT NULL DEFAULT now(),
    CHECK (expires_on IS NULL OR expires_on >= effective_on)
);

CREATE INDEX storage_facility_price_lookup_idx
    ON overview.storage_facility_price(facility_code,product_code,effective_on DESC);

CREATE TABLE overview.storage_facility_evidence (
    evidence_id uuid PRIMARY KEY,
    facility_code varchar(60) NOT NULL REFERENCES overview.storage_facility(facility_code) ON DELETE CASCADE,
    evidence_kind varchar(30) NOT NULL
        CHECK (evidence_kind IN ('RELATIONSHIP','ADDRESS','COORDINATE','CAPACITY','PRICE','STATUS')),
    title varchar(300) NOT NULL,
    source_name varchar(200) NOT NULL,
    source_url text NOT NULL,
    source_classification varchar(20) NOT NULL
        CHECK (source_classification IN ('INTERNAL_GOVERNED','PUBLIC')),
    source_as_of date,
    verified_at timestamptz NOT NULL DEFAULT now(),
    note text,
    UNIQUE(facility_code,evidence_kind,source_url)
);

COMMENT ON TABLE overview.storage_facility IS
    'Governed associated-storage catalogue. Relationship, capacity and price facts must remain null unless supported by retained evidence.';
COMMENT ON COLUMN overview.storage_facility.coordinate_precision IS
    'EXACT is a verified site point; STREET and TOWN are visibly disclosed approximations and must not be presented as exact.';
COMMENT ON TABLE overview.storage_facility_price IS
    'Time-bounded published or internally governed acquisition prices. Historical prices are never presented as current.';

GRANT SELECT ON overview.storage_facility,overview.storage_facility_price,overview.storage_facility_evidence
    TO qiqihar_enterprise_runtime;

-- This initial public record is deliberately conservative. The relation is supported by a public
-- company disclosure, while the coordinate is only the OpenStreetMap centreline of the published
-- street address and is therefore labelled STREET rather than an exact depot location.
INSERT INTO overview.storage_facility(
    facility_code,facility_name,work_unit_code,relation_type,region_code,address,geometry,
    coordinate_precision,operational_status,valid_from,updated_at
) VALUES (
    'KESHAN_DEPOT','中粮贸易（克山）粮食储运有限公司','KESHAN_DEPOT','OWNED','230229',
    '黑龙江省齐齐哈尔市克山县春风大街36号',
    ST_SetSRID(ST_Point(125.8476007,48.0252548),4326),'STREET','ACTIVE',DATE '2019-11-13',now()
);

INSERT INTO overview.storage_facility_evidence(
    evidence_id,facility_code,evidence_kind,title,source_name,source_url,
    source_classification,source_as_of,verified_at,note
) VALUES
('150a8198-526c-4fe4-84bc-a755da99dd45','KESHAN_DEPOT','RELATIONSHIP',
 '中粮体系关联企业披露','巨潮资讯网',
 'https://static.cninfo.com.cn/finalpage/2024-11-23/1221816827.PDF','PUBLIC',DATE '2024-11-23',now(),
 '披露该企业由同一最终控制方控制；系统关系类型为自有库点。'),
('3e873f17-e5c3-4979-b7aa-72db131a8103','KESHAN_DEPOT','ADDRESS',
 '黑龙江省第一批2019年最低收购价稻谷委托收储资格库点名单','黑龙江省粮食主管部门公开名单转载',
 'https://m.thepaper.cn/newsDetail_forward_4945728','PUBLIC',DATE '2019-11-13',now(),
 '名单载明企业地址为克山县春风大街36号。'),
('1a12ed5a-2220-4fe8-8e16-e4dc48936611','KESHAN_DEPOT','COORDINATE',
 '春风大街公开地图中心线','OpenStreetMap contributors',
 'https://www.openstreetmap.org/way/261211378','PUBLIC',DATE '2026-09-18',now(),
 '仅为公开地址所在道路中心线，地图必须展示为街道级近似位置。'),
('bd666cf2-4197-4342-959b-15529f1ba0db','KESHAN_DEPOT','PRICE',
 '2025年12月5日大豆收购公告','Mysteel 我的钢铁网',
 'https://m.mysteel.com/a/25120510/3BF8558A0CA33A98_abc.html','PUBLIC',DATE '2025-12-05',now(),
 '历史公开收购价，按蛋白含量分档；不得作为当前报价。');

INSERT INTO overview.storage_facility_price(
    price_id,facility_code,product_code,quality_requirement,price_value,price_unit,effective_on,
    source_url,source_name,source_classification,captured_at
) VALUES
('35911626-ebca-4f45-a06f-58889db74c18','KESHAN_DEPOT','SOYBEAN','34<蛋白<36',1.85,'元/斤',DATE '2025-12-05',
 'https://m.mysteel.com/a/25120510/3BF8558A0CA33A98_abc.html','Mysteel 我的钢铁网','PUBLIC',now()),
('b64d770c-c4d8-4b4b-bacf-873a1b508353','KESHAN_DEPOT','SOYBEAN','36≤蛋白<38',1.88,'元/斤',DATE '2025-12-05',
 'https://m.mysteel.com/a/25120510/3BF8558A0CA33A98_abc.html','Mysteel 我的钢铁网','PUBLIC',now()),
('709c2286-032c-42de-85d7-5c6d73175b52','KESHAN_DEPOT','SOYBEAN','39≤蛋白<39.5',1.97,'元/斤',DATE '2025-12-05',
 'https://m.mysteel.com/a/25120510/3BF8558A0CA33A98_abc.html','Mysteel 我的钢铁网','PUBLIC',now()),
('ef738c87-53ae-428c-a706-fd5f43fd1325','KESHAN_DEPOT','SOYBEAN','42≤蛋白',2.145,'元/斤',DATE '2025-12-05',
 'https://m.mysteel.com/a/25120510/3BF8558A0CA33A98_abc.html','Mysteel 我的钢铁网','PUBLIC',now());
