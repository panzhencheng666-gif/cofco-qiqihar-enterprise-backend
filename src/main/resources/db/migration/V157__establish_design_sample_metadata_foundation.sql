CREATE TABLE platform.design_sample_contract (
    contract_version varchar(80) PRIMARY KEY,
    contract_digest varchar(71) NOT NULL
        CHECK (contract_digest ~ '^sha256:[a-f0-9]{64}$'),
    active boolean NOT NULL,
    activated_at timestamptz NOT NULL,
    UNIQUE (contract_digest)
);

CREATE UNIQUE INDEX design_sample_contract_one_active
    ON platform.design_sample_contract(active)
    WHERE active;

CREATE TABLE platform.design_sample_domain_definition (
    contract_version varchar(80) NOT NULL
        REFERENCES platform.design_sample_contract(contract_version),
    code varchar(40) NOT NULL,
    name varchar(100) NOT NULL,
    description varchar(500) NOT NULL,
    aliases jsonb NOT NULL DEFAULT '[]'::jsonb
        CHECK (jsonb_typeof(aliases) = 'array'),
    sort_order integer NOT NULL CHECK (sort_order > 0),
    PRIMARY KEY (contract_version, code),
    UNIQUE (contract_version, sort_order)
);

CREATE TABLE platform.design_sample_product_definition (
    contract_version varchar(80) NOT NULL
        REFERENCES platform.design_sample_contract(contract_version),
    code varchar(40) NOT NULL,
    name varchar(100) NOT NULL,
    aliases jsonb NOT NULL DEFAULT '[]'::jsonb
        CHECK (jsonb_typeof(aliases) = 'array'),
    sort_order integer NOT NULL CHECK (sort_order > 0),
    PRIMARY KEY (contract_version, code),
    UNIQUE (contract_version, sort_order)
);

CREATE TABLE platform.design_sample_object_type_definition (
    contract_version varchar(80) NOT NULL,
    domain_code varchar(40) NOT NULL,
    code varchar(80) NOT NULL,
    name varchar(100) NOT NULL,
    aliases jsonb NOT NULL DEFAULT '[]'::jsonb
        CHECK (jsonb_typeof(aliases) = 'array'),
    sort_order integer NOT NULL CHECK (sort_order > 0),
    PRIMARY KEY (contract_version, domain_code, code),
    UNIQUE (contract_version, code),
    UNIQUE (contract_version, domain_code, sort_order),
    FOREIGN KEY (contract_version, domain_code)
        REFERENCES platform.design_sample_domain_definition(contract_version, code)
);

CREATE TABLE platform.design_sample_context (
    contract_version varchar(80) NOT NULL,
    domain_code varchar(40) NOT NULL,
    product_code varchar(40) NOT NULL,
    object_type_code varchar(80) NOT NULL,
    sort_order integer NOT NULL CHECK (sort_order > 0),
    PRIMARY KEY (contract_version, domain_code, product_code, object_type_code),
    UNIQUE (contract_version, sort_order),
    FOREIGN KEY (contract_version, domain_code)
        REFERENCES platform.design_sample_domain_definition(contract_version, code),
    FOREIGN KEY (contract_version, product_code)
        REFERENCES platform.design_sample_product_definition(contract_version, code),
    FOREIGN KEY (contract_version, domain_code, object_type_code)
        REFERENCES platform.design_sample_object_type_definition(
            contract_version, domain_code, code)
);

CREATE TABLE platform.design_sample_field_definition (
    contract_version varchar(80) NOT NULL
        REFERENCES platform.design_sample_contract(contract_version),
    code varchar(100) NOT NULL,
    section_code varchar(20) NOT NULL
        CHECK (section_code IN ('IDENTITY', 'OBSERVATION')),
    label varchar(160) NOT NULL,
    description varchar(500) NOT NULL,
    value_type varchar(20) NOT NULL
        CHECK (value_type IN ('UUID', 'STRING', 'DATE', 'DECIMAL', 'ENUM')),
    numeric_precision integer,
    numeric_scale integer,
    max_length integer,
    unit varchar(80),
    enum_options jsonb NOT NULL DEFAULT '[]'::jsonb
        CHECK (jsonb_typeof(enum_options) = 'array'),
    required boolean NOT NULL,
    nullable boolean NOT NULL,
    default_value jsonb,
    editable boolean NOT NULL,
    minimum_value numeric,
    maximum_value numeric,
    analysis_role varchar(100) NOT NULL,
    PRIMARY KEY (contract_version, code),
    CHECK (NOT required OR NOT nullable),
    CHECK ((value_type = 'DECIMAL') = (numeric_precision IS NOT NULL)),
    CHECK ((value_type = 'DECIMAL') = (numeric_scale IS NOT NULL)),
    CHECK (numeric_precision IS NULL OR numeric_precision > 0),
    CHECK (numeric_scale IS NULL OR numeric_scale >= 0),
    CHECK (max_length IS NULL OR max_length > 0),
    CHECK ((value_type = 'ENUM') = (jsonb_array_length(enum_options) > 0)),
    CHECK (minimum_value IS NULL OR maximum_value IS NULL OR minimum_value <= maximum_value)
);

CREATE TABLE platform.design_sample_field_applicability (
    contract_version varchar(80) NOT NULL,
    domain_code varchar(40) NOT NULL,
    product_code varchar(40) NOT NULL,
    object_type_code varchar(80) NOT NULL,
    field_code varchar(100) NOT NULL,
    group_code varchar(80) NOT NULL,
    sort_order integer NOT NULL CHECK (sort_order > 0),
    PRIMARY KEY (
        contract_version, domain_code, product_code, object_type_code, field_code),
    UNIQUE (
        contract_version, domain_code, product_code, object_type_code, sort_order),
    FOREIGN KEY (contract_version, domain_code, product_code, object_type_code)
        REFERENCES platform.design_sample_context(
            contract_version, domain_code, product_code, object_type_code),
    FOREIGN KEY (contract_version, field_code)
        REFERENCES platform.design_sample_field_definition(contract_version, code)
);

CREATE TABLE platform.design_sample_alias_definition (
    contract_version varchar(80) NOT NULL
        REFERENCES platform.design_sample_contract(contract_version),
    alias_type varchar(40) NOT NULL
        CHECK (alias_type IN ('PRODUCT_LABEL', 'PRODUCT_SLUG', 'OBJECT_LABEL', 'OBJECT_SLUG')),
    domain_code varchar(40) NOT NULL DEFAULT '',
    alias_code varchar(160) NOT NULL,
    canonical_code varchar(100) NOT NULL,
    PRIMARY KEY (contract_version, alias_type, domain_code, alias_code)
);

INSERT INTO platform.design_sample_contract(
    contract_version, contract_digest, active, activated_at)
VALUES (
    'design-sample-fields-v1',
    'sha256:0000000000000000000000000000000000000000000000000000000000000000',
    true,
    CURRENT_TIMESTAMP);

INSERT INTO platform.design_sample_domain_definition(
    contract_version, code, name, description, aliases, sort_order)
VALUES
    ('design-sample-fields-v1', 'PRODUCTION', '产情域',
     '种植、长势、面积、单产、产量及农户余粮等产情观测', '[]', 10),
    ('design-sample-fields-v1', 'MARKET', '市场域',
     '粮食经营主体和农资店的价格、数量、库存、加工、供货与意向观测', '[]', 20);

INSERT INTO platform.design_sample_product_definition(
    contract_version, code, name, aliases, sort_order)
VALUES
    ('design-sample-fields-v1', 'CORN', '玉米', '[]', 10),
    ('design-sample-fields-v1', 'SOYBEAN', '大豆', '[]', 20),
    ('design-sample-fields-v1', 'RICE', '水稻', '["稻谷"]', 30);

INSERT INTO platform.design_sample_object_type_definition(
    contract_version, domain_code, code, name, aliases, sort_order)
VALUES
    ('design-sample-fields-v1', 'PRODUCTION', 'FARMER', '农户', '[]', 10),
    ('design-sample-fields-v1', 'PRODUCTION', 'VILLAGE_COMMITTEE', '村委会', '[]', 20),
    ('design-sample-fields-v1', 'PRODUCTION', 'AGRICULTURAL_TECH_STATION', '农技站', '[]', 30),
    ('design-sample-fields-v1', 'MARKET', 'TRADER', '贸易商', '[]', 110),
    ('design-sample-fields-v1', 'MARKET', 'DEEP_PROCESSOR', '深加工企业', '["深加工"]', 120),
    ('design-sample-fields-v1', 'MARKET', 'WHOLESALE_MARKET', '批发市场', '[]', 130),
    ('design-sample-fields-v1', 'MARKET', 'RESERVE_ENTERPRISE', '承储企业', '[]', 140),
    ('design-sample-fields-v1', 'MARKET', 'RICE_MILL', '米厂', '[]', 150),
    ('design-sample-fields-v1', 'MARKET', 'BREEDING_FACTORY', '养殖场', '["养殖厂"]', 160),
    ('design-sample-fields-v1', 'MARKET', 'FEED_MILL', '饲料厂', '[]', 170),
    ('design-sample-fields-v1', 'MARKET', 'AGRICULTURAL_INPUT_STORE', '农资店', '[]', 180);

INSERT INTO platform.design_sample_context(
    contract_version, domain_code, product_code, object_type_code, sort_order)
VALUES
    ('design-sample-fields-v1', 'PRODUCTION', 'CORN', 'FARMER', 10),
    ('design-sample-fields-v1', 'PRODUCTION', 'SOYBEAN', 'FARMER', 20),
    ('design-sample-fields-v1', 'PRODUCTION', 'RICE', 'FARMER', 30),
    ('design-sample-fields-v1', 'PRODUCTION', 'CORN', 'VILLAGE_COMMITTEE', 40),
    ('design-sample-fields-v1', 'PRODUCTION', 'SOYBEAN', 'VILLAGE_COMMITTEE', 50),
    ('design-sample-fields-v1', 'PRODUCTION', 'RICE', 'VILLAGE_COMMITTEE', 60),
    ('design-sample-fields-v1', 'PRODUCTION', 'CORN', 'AGRICULTURAL_TECH_STATION', 70),
    ('design-sample-fields-v1', 'PRODUCTION', 'SOYBEAN', 'AGRICULTURAL_TECH_STATION', 80),
    ('design-sample-fields-v1', 'PRODUCTION', 'RICE', 'AGRICULTURAL_TECH_STATION', 90),
    ('design-sample-fields-v1', 'MARKET', 'CORN', 'TRADER', 110),
    ('design-sample-fields-v1', 'MARKET', 'SOYBEAN', 'TRADER', 120),
    ('design-sample-fields-v1', 'MARKET', 'RICE', 'TRADER', 130),
    ('design-sample-fields-v1', 'MARKET', 'CORN', 'DEEP_PROCESSOR', 140),
    ('design-sample-fields-v1', 'MARKET', 'SOYBEAN', 'DEEP_PROCESSOR', 150),
    ('design-sample-fields-v1', 'MARKET', 'RICE', 'DEEP_PROCESSOR', 160),
    ('design-sample-fields-v1', 'MARKET', 'CORN', 'WHOLESALE_MARKET', 170),
    ('design-sample-fields-v1', 'MARKET', 'SOYBEAN', 'WHOLESALE_MARKET', 180),
    ('design-sample-fields-v1', 'MARKET', 'RICE', 'WHOLESALE_MARKET', 190),
    ('design-sample-fields-v1', 'MARKET', 'CORN', 'RESERVE_ENTERPRISE', 200),
    ('design-sample-fields-v1', 'MARKET', 'SOYBEAN', 'RESERVE_ENTERPRISE', 210),
    ('design-sample-fields-v1', 'MARKET', 'RICE', 'RESERVE_ENTERPRISE', 220),
    ('design-sample-fields-v1', 'MARKET', 'RICE', 'RICE_MILL', 230),
    ('design-sample-fields-v1', 'MARKET', 'CORN', 'BREEDING_FACTORY', 240),
    ('design-sample-fields-v1', 'MARKET', 'CORN', 'FEED_MILL', 250),
    ('design-sample-fields-v1', 'MARKET', 'CORN', 'AGRICULTURAL_INPUT_STORE', 260),
    ('design-sample-fields-v1', 'MARKET', 'SOYBEAN', 'AGRICULTURAL_INPUT_STORE', 270),
    ('design-sample-fields-v1', 'MARKET', 'RICE', 'AGRICULTURAL_INPUT_STORE', 280);

INSERT INTO platform.design_sample_field_definition(
    contract_version, code, section_code, label, description, value_type,
    numeric_precision, numeric_scale, max_length, unit, enum_options,
    required, nullable, default_value, editable, minimum_value, maximum_value,
    analysis_role)
VALUES
    ('design-sample-fields-v1', 'DSP_ID', 'IDENTITY', '设计样本点ID',
     '服务端生成的稳定标识，不使用来源表序号', 'UUID', NULL, NULL, NULL, NULL, '[]',
     true, false, NULL, false, NULL, NULL, 'NONE'),
    ('design-sample-fields-v1', 'DOMAIN_CODE', 'IDENTITY', '业务域',
     '设计样本点所属业务域', 'ENUM', NULL, NULL, NULL, NULL, '["PRODUCTION","MARKET"]',
     true, false, NULL, false, NULL, NULL, 'NONE'),
    ('design-sample-fields-v1', 'PRODUCT_CODE', 'IDENTITY', '产品',
     '设计样本点所选稳定产品代码', 'ENUM', NULL, NULL, NULL, NULL, '["CORN","SOYBEAN","RICE"]',
     true, false, NULL, false, NULL, NULL, 'NONE'),
    ('design-sample-fields-v1', 'OBJECT_TYPE_CODE', 'IDENTITY', '对象类型',
     '设计样本点所选稳定对象代码', 'ENUM', NULL, NULL, NULL, NULL,
     '["FARMER","VILLAGE_COMMITTEE","AGRICULTURAL_TECH_STATION","TRADER","DEEP_PROCESSOR","WHOLESALE_MARKET","RESERVE_ENTERPRISE","RICE_MILL","BREEDING_FACTORY","FEED_MILL","AGRICULTURAL_INPUT_STORE"]',
     true, false, NULL, false, NULL, NULL, 'NONE'),
    ('design-sample-fields-v1', 'DSP_NAME', 'IDENTITY', '点位名称',
     '对象或点位的业务名称', 'STRING', NULL, NULL, 200, NULL, '[]',
     true, false, NULL, true, NULL, NULL, 'NONE'),
    ('design-sample-fields-v1', 'DSP_REGION_CODE', 'IDENTITY', '行政区代码',
     '所选最细行政区稳定代码', 'STRING', NULL, NULL, 12, NULL, '[]',
     true, false, NULL, true, NULL, NULL, 'NONE'),
    ('design-sample-fields-v1', 'DSP_LONGITUDE', 'IDENTITY', '经度',
     'WGS84 经度', 'DECIMAL', 10, 7, NULL, '度', '[]',
     true, false, NULL, true, -180, 180, 'NONE'),
    ('design-sample-fields-v1', 'DSP_LATITUDE', 'IDENTITY', '纬度',
     'WGS84 纬度', 'DECIMAL', 10, 7, NULL, '度', '[]',
     true, false, NULL, true, -90, 90, 'NONE'),
    ('design-sample-fields-v1', 'DSP_COORDINATE_SOURCE', 'IDENTITY', '坐标来源',
     '坐标来源名称', 'STRING', NULL, NULL, 200, NULL, '[]',
     false, true, NULL, true, NULL, NULL, 'NONE'),
    ('design-sample-fields-v1', 'DSP_COORDINATE_SOURCE_REVISION', 'IDENTITY', '坐标来源版本',
     '坐标来源版本或修订号', 'STRING', NULL, NULL, 80, NULL, '[]',
     false, true, NULL, true, NULL, NULL, 'NONE'),
    ('design-sample-fields-v1', 'DSP_REGION_PATH', 'IDENTITY', '行政区路径',
     '由权威行政区主数据派生的展示路径', 'STRING', NULL, NULL, NULL, NULL, '[]',
     true, false, NULL, false, NULL, NULL, 'NONE'),
    ('design-sample-fields-v1', 'OBSERVED_ON', 'OBSERVATION', '观测日期',
     '业务观测发生日期', 'DATE', NULL, NULL, NULL, NULL, '[]',
     true, false, NULL, true, NULL, NULL, 'NONE'),

    ('design-sample-fields-v1', 'PROD_AREA_MU', 'OBSERVATION', '播种面积',
     '所选产品播种面积', 'DECIMAL', 18, 4, NULL, '亩', '[]',
     false, true, NULL, true, 0, NULL, 'SUM_NONNULL'),
    ('design-sample-fields-v1', 'PROD_HARVEST_AREA_MU', 'OBSERVATION', '预计收获面积',
     '所选产品预计收获面积', 'DECIMAL', 18, 4, NULL, '亩', '[]',
     false, true, NULL, true, 0, NULL, 'SUM_NONNULL'),
    ('design-sample-fields-v1', 'PROD_AFFECTED_AREA_MU', 'OBSERVATION', '灾损面积',
     '所选产品灾损面积', 'DECIMAL', 18, 4, NULL, '亩', '[]',
     false, true, NULL, true, 0, NULL, 'SUM_NONNULL'),
    ('design-sample-fields-v1', 'PROD_YIELD_PER_MU', 'OBSERVATION', '预计单产',
     '单位面积预计产量', 'DECIMAL', 18, 4, NULL, '公斤/亩', '[]',
     false, true, NULL, true, 0, NULL, 'WEIGHTED_AVG_BY_PROD_AREA_MU'),
    ('design-sample-fields-v1', 'PROD_ESTIMATED_OUTPUT', 'OBSERVATION', '预计总产',
     '播种面积乘以预计单产的只读派生值', 'DECIMAL', 18, 4, NULL, '公斤', '[]',
     false, true, NULL, false, 0, NULL, 'SUM_DERIVED_NONNULL'),
    ('design-sample-fields-v1', 'PROD_GROWTH_STATUS', 'OBSERVATION', '当前长势',
     '当前作物长势状态', 'ENUM', NULL, NULL, NULL, NULL, '["GOOD","NORMAL","POOR"]',
     false, true, NULL, true, NULL, NULL, 'DISTRIBUTION_NONNULL'),
    ('design-sample-fields-v1', 'PROD_GROWTH_STAGE', 'OBSERVATION', '当前生育阶段',
     '按产品使用的业务阶段文本', 'STRING', NULL, NULL, 100, NULL, '[]',
     false, true, NULL, true, NULL, NULL, 'DISTRIBUTION_NONNULL'),
    ('design-sample-fields-v1', 'PROD_OPENING_INVENTORY', 'OBSERVATION', '期初库存/余粮',
     '本期开始时库存或余粮', 'DECIMAL', 18, 4, NULL, '吨', '[]',
     false, true, NULL, true, 0, NULL, 'SUM_NONNULL'),
    ('design-sample-fields-v1', 'PROD_SALES_VOLUME', 'OBSERVATION', '本期销售数量',
     '本期销售数量', 'DECIMAL', 18, 4, NULL, '吨', '[]',
     false, true, NULL, true, 0, NULL, 'SUM_NONNULL'),
    ('design-sample-fields-v1', 'PROD_SELF_USE', 'OBSERVATION', '本期自用数量',
     '本期自用数量', 'DECIMAL', 18, 4, NULL, '吨', '[]',
     false, true, NULL, true, 0, NULL, 'SUM_NONNULL'),
    ('design-sample-fields-v1', 'PROD_ENDING_INVENTORY', 'OBSERVATION', '期末余粮',
     '本期结束时余粮', 'DECIMAL', 18, 4, NULL, '吨', '[]',
     false, true, NULL, true, 0, NULL, 'LATEST_SUM_NONNULL'),
    ('design-sample-fields-v1', 'PROD_INTENDED_AREA_MU', 'OBSERVATION', '下年度意向面积',
     '下一年度种植意向面积', 'DECIMAL', 18, 4, NULL, '亩', '[]',
     false, true, NULL, true, 0, NULL, 'SUM_NONNULL'),
    ('design-sample-fields-v1', 'PROD_INTENTION_REASON', 'OBSERVATION', '种植意向调整原因',
     '种植意向变化的业务原因', 'STRING', NULL, NULL, 500, NULL, '[]',
     false, true, NULL, true, NULL, NULL, 'NONE'),

    ('design-sample-fields-v1', 'MOISTURE', 'OBSERVATION', '水分',
     '产品水分比例', 'DECIMAL', 18, 4, NULL, '%', '[]',
     false, true, NULL, true, 0, 100, 'AVG_NONNULL'),
    ('design-sample-fields-v1', 'TEST_WEIGHT', 'OBSERVATION', '容重',
     '产品容重', 'DECIMAL', 18, 4, NULL, '克/升', '[]',
     false, true, NULL, true, 0, NULL, 'AVG_NONNULL'),
    ('design-sample-fields-v1', 'TOXIN', 'OBSERVATION', '毒素',
     '玉米毒素比例', 'DECIMAL', 18, 4, NULL, '%', '[]',
     false, true, NULL, true, 0, 100, 'AVG_NONNULL'),
    ('design-sample-fields-v1', 'IMPURITY', 'OBSERVATION', '杂质',
     '产品杂质比例', 'DECIMAL', 18, 4, NULL, '%', '[]',
     false, true, NULL, true, 0, 100, 'AVG_NONNULL'),
    ('design-sample-fields-v1', 'IMPERFECT_GRAIN', 'OBSERVATION', '不完善粒',
     '不完善粒比例', 'DECIMAL', 18, 4, NULL, '%', '[]',
     false, true, NULL, true, 0, 100, 'AVG_NONNULL'),
    ('design-sample-fields-v1', 'MILDEW', 'OBSERVATION', '霉变粒',
     '霉变粒比例', 'DECIMAL', 18, 4, NULL, '%', '[]',
     false, true, NULL, true, 0, 100, 'AVG_NONNULL'),
    ('design-sample-fields-v1', 'PROTEIN', 'OBSERVATION', '蛋白',
     '大豆蛋白比例', 'DECIMAL', 18, 4, NULL, '%', '[]',
     false, true, NULL, true, 0, 100, 'AVG_NONNULL'),
    ('design-sample-fields-v1', 'OIL_YIELD', 'OBSERVATION', '含油率',
     '大豆含油比例', 'DECIMAL', 18, 4, NULL, '%', '[]',
     false, true, NULL, true, 0, 100, 'AVG_NONNULL'),
    ('design-sample-fields-v1', 'MILLING_YIELD', 'OBSERVATION', '出米率',
     '水稻出米比例', 'DECIMAL', 18, 4, NULL, '%', '[]',
     false, true, NULL, true, 0, 100, 'AVG_NONNULL'),
    ('design-sample-fields-v1', 'BROWN_RICE_YIELD', 'OBSERVATION', '出糙率',
     '水稻出糙比例', 'DECIMAL', 18, 4, NULL, '%', '[]',
     false, true, NULL, true, 0, 100, 'AVG_NONNULL'),

    ('design-sample-fields-v1', 'MKT_PURCHASE_BASE_PRICE', 'OBSERVATION', '收购基础价',
     '对象从上游收购所选粮食的基础价', 'DECIMAL', 18, 4, NULL, '元/吨', '[]',
     false, true, NULL, true, 0, NULL, 'AVG_NONNULL'),
    ('design-sample-fields-v1', 'MKT_SALE_BASE_PRICE', 'OBSERVATION', '销售基础价',
     '贸易商向下游销售所选粮食的基础价', 'DECIMAL', 18, 4, NULL, '元/吨', '[]',
     false, true, NULL, true, 0, NULL, 'AVG_NONNULL_TRADER_ONLY'),
    ('design-sample-fields-v1', 'PURCHASE_VOLUME', 'OBSERVATION', '本期收购量',
     '本期收购量', 'DECIMAL', 18, 4, NULL, '吨', '[]',
     false, true, NULL, true, 0, NULL, 'SUM_NONNULL'),
    ('design-sample-fields-v1', 'SALES_VOLUME', 'OBSERVATION', '本期销售量',
     '本期销售量', 'DECIMAL', 18, 4, NULL, '吨', '[]',
     false, true, NULL, true, 0, NULL, 'SUM_NONNULL'),
    ('design-sample-fields-v1', 'PROCESSING_INPUT', 'OBSERVATION', '日加工投入量',
     '日加工投入量', 'DECIMAL', 18, 4, NULL, '吨/日', '[]',
     false, true, NULL, true, 0, NULL, 'AVG_NONNULL'),
    ('design-sample-fields-v1', 'MAIN_OUTPUT', 'OBSERVATION', '日主产品产出量',
     '日主产品产出量', 'DECIMAL', 18, 4, NULL, '吨/日', '[]',
     false, true, NULL, true, 0, NULL, 'AVG_NONNULL'),
    ('design-sample-fields-v1', 'BYPRODUCT_OUTPUT', 'OBSERVATION', '日副产品产出量',
     '日副产品产出量', 'DECIMAL', 18, 4, NULL, '吨/日', '[]',
     false, true, NULL, true, 0, NULL, 'AVG_NONNULL'),
    ('design-sample-fields-v1', 'PROCESSING_LOSS', 'OBSERVATION', '日加工损耗量',
     '日加工损耗量', 'DECIMAL', 18, 4, NULL, '吨/日', '[]',
     false, true, NULL, true, 0, NULL, 'AVG_NONNULL'),
    ('design-sample-fields-v1', 'OPENING_INVENTORY', 'OBSERVATION', '期初库存',
     '期初库存', 'DECIMAL', 18, 4, NULL, '吨', '[]',
     false, true, NULL, true, 0, NULL, 'SUM_NONNULL'),
    ('design-sample-fields-v1', 'STOCK_OUTFLOW', 'OBSERVATION', '本期出库量',
     '本期出库量', 'DECIMAL', 18, 4, NULL, '吨', '[]',
     false, true, NULL, true, 0, NULL, 'SUM_NONNULL'),
    ('design-sample-fields-v1', 'ENDING_INVENTORY', 'OBSERVATION', '期末库存',
     '期末库存', 'DECIMAL', 18, 4, NULL, '吨', '[]',
     false, true, NULL, true, 0, NULL, 'LATEST_SUM_NONNULL'),

    ('design-sample-fields-v1', 'AGRI_INPUT_SEED_SALES_VOLUME', 'OBSERVATION', '种子销售量',
     '所选作物种子的本期销售量', 'DECIMAL', 18, 4, NULL, '公斤', '[]',
     false, true, NULL, true, 0, NULL, 'SUM_NONNULL'),
    ('design-sample-fields-v1', 'AGRI_INPUT_SEED_RETAIL_PRICE', 'OBSERVATION', '种子零售价',
     '所选作物种子的零售价', 'DECIMAL', 18, 4, NULL, '元/公斤', '[]',
     false, true, NULL, true, 0, NULL, 'AVG_NONNULL'),
    ('design-sample-fields-v1', 'AGRI_INPUT_SUPPLY_STATUS', 'OBSERVATION', '供货状态',
     '所选作物种子的供货状态', 'ENUM', NULL, NULL, NULL, NULL,
     '["SUFFICIENT","NORMAL","TIGHT","OUT_OF_STOCK"]',
     false, true, NULL, true, NULL, NULL, 'DISTRIBUTION_NONNULL'),
    ('design-sample-fields-v1', 'AGRI_INPUT_PLANTING_INTENTION_TREND', 'OBSERVATION', '种植意向趋势',
     '顾客对所选作物的种植意向趋势', 'ENUM', NULL, NULL, NULL, NULL,
     '["INCREASE","STABLE","DECREASE"]',
     false, true, NULL, true, NULL, NULL, 'DISTRIBUTION_NONNULL');

WITH identity_fields(field_code, group_code, sort_order) AS (
    VALUES
        ('DSP_ID', 'IDENTITY', 10),
        ('DOMAIN_CODE', 'IDENTITY', 20),
        ('PRODUCT_CODE', 'IDENTITY', 30),
        ('OBJECT_TYPE_CODE', 'IDENTITY', 40),
        ('DSP_NAME', 'IDENTITY', 50),
        ('DSP_REGION_CODE', 'IDENTITY', 60),
        ('DSP_LONGITUDE', 'IDENTITY', 70),
        ('DSP_LATITUDE', 'IDENTITY', 80),
        ('DSP_COORDINATE_SOURCE', 'IDENTITY', 90),
        ('DSP_COORDINATE_SOURCE_REVISION', 'IDENTITY', 100),
        ('DSP_REGION_PATH', 'IDENTITY', 110),
        ('OBSERVED_ON', 'OBSERVATION', 200)
)
INSERT INTO platform.design_sample_field_applicability(
    contract_version, domain_code, product_code, object_type_code,
    field_code, group_code, sort_order)
SELECT context.contract_version, context.domain_code, context.product_code,
       context.object_type_code, field.field_code, field.group_code, field.sort_order
FROM platform.design_sample_context context
CROSS JOIN identity_fields field;

WITH production_fields(object_type_code, field_code, group_code, sort_order) AS (
    VALUES
        ('*', 'PROD_AREA_MU', 'PRODUCTION_OUTPUT', 310),
        ('*', 'PROD_HARVEST_AREA_MU', 'PRODUCTION_OUTPUT', 320),
        ('*', 'PROD_AFFECTED_AREA_MU', 'PRODUCTION_OUTPUT', 330),
        ('*', 'PROD_YIELD_PER_MU', 'PRODUCTION_OUTPUT', 340),
        ('*', 'PROD_ESTIMATED_OUTPUT', 'PRODUCTION_OUTPUT', 350),
        ('*', 'PROD_GROWTH_STATUS', 'PRODUCTION_GROWTH', 410),
        ('*', 'PROD_GROWTH_STAGE', 'PRODUCTION_GROWTH', 420),
        ('FARMER', 'PROD_OPENING_INVENTORY', 'PRODUCTION_INVENTORY', 510),
        ('FARMER', 'PROD_SALES_VOLUME', 'PRODUCTION_INVENTORY', 520),
        ('FARMER', 'PROD_SELF_USE', 'PRODUCTION_INVENTORY', 530),
        ('FARMER', 'PROD_ENDING_INVENTORY', 'PRODUCTION_INVENTORY', 540),
        ('FARMER', 'PROD_INTENDED_AREA_MU', 'PRODUCTION_INTENTION', 610),
        ('FARMER', 'PROD_INTENTION_REASON', 'PRODUCTION_INTENTION', 620),
        ('VILLAGE_COMMITTEE', 'PROD_INTENDED_AREA_MU', 'PRODUCTION_INTENTION', 610),
        ('VILLAGE_COMMITTEE', 'PROD_INTENTION_REASON', 'PRODUCTION_INTENTION', 620)
)
INSERT INTO platform.design_sample_field_applicability(
    contract_version, domain_code, product_code, object_type_code,
    field_code, group_code, sort_order)
SELECT context.contract_version, context.domain_code, context.product_code,
       context.object_type_code, field.field_code, field.group_code, field.sort_order
FROM platform.design_sample_context context
JOIN production_fields field
  ON field.object_type_code = '*' OR field.object_type_code = context.object_type_code
WHERE context.domain_code = 'PRODUCTION';

WITH quality_fields(product_code, field_code, sort_order) AS (
    VALUES
        ('CORN', 'MOISTURE', 710),
        ('CORN', 'TEST_WEIGHT', 720),
        ('CORN', 'TOXIN', 730),
        ('CORN', 'IMPURITY', 740),
        ('CORN', 'IMPERFECT_GRAIN', 750),
        ('CORN', 'MILDEW', 760),
        ('SOYBEAN', 'PROTEIN', 710),
        ('SOYBEAN', 'OIL_YIELD', 720),
        ('SOYBEAN', 'IMPERFECT_GRAIN', 730),
        ('SOYBEAN', 'MOISTURE', 740),
        ('SOYBEAN', 'IMPURITY', 750),
        ('RICE', 'MOISTURE', 710),
        ('RICE', 'MILLING_YIELD', 720),
        ('RICE', 'BROWN_RICE_YIELD', 730),
        ('RICE', 'IMPURITY', 740)
)
INSERT INTO platform.design_sample_field_applicability(
    contract_version, domain_code, product_code, object_type_code,
    field_code, group_code, sort_order)
SELECT context.contract_version, context.domain_code, context.product_code,
       context.object_type_code, field.field_code, 'QUALITY', field.sort_order
FROM platform.design_sample_context context
JOIN quality_fields field ON field.product_code = context.product_code
WHERE context.domain_code = 'PRODUCTION'
  AND context.object_type_code IN ('FARMER', 'AGRICULTURAL_TECH_STATION');

WITH market_fields(object_type_code, field_code, group_code, sort_order) AS (
    VALUES
        ('TRADER', 'MKT_PURCHASE_BASE_PRICE', 'PRICE', 310),
        ('TRADER', 'MKT_SALE_BASE_PRICE', 'PRICE', 320),
        ('TRADER', 'PURCHASE_VOLUME', 'VOLUME', 410),
        ('TRADER', 'SALES_VOLUME', 'VOLUME', 420),
        ('TRADER', 'OPENING_INVENTORY', 'INVENTORY', 610),
        ('TRADER', 'STOCK_OUTFLOW', 'INVENTORY', 620),
        ('TRADER', 'ENDING_INVENTORY', 'INVENTORY', 630),
        ('DEEP_PROCESSOR', 'MKT_PURCHASE_BASE_PRICE', 'PRICE', 310),
        ('DEEP_PROCESSOR', 'PURCHASE_VOLUME', 'VOLUME', 410),
        ('DEEP_PROCESSOR', 'PROCESSING_INPUT', 'PROCESSING', 510),
        ('DEEP_PROCESSOR', 'MAIN_OUTPUT', 'PROCESSING', 520),
        ('DEEP_PROCESSOR', 'BYPRODUCT_OUTPUT', 'PROCESSING', 530),
        ('DEEP_PROCESSOR', 'PROCESSING_LOSS', 'PROCESSING', 540),
        ('DEEP_PROCESSOR', 'OPENING_INVENTORY', 'INVENTORY', 610),
        ('DEEP_PROCESSOR', 'STOCK_OUTFLOW', 'INVENTORY', 620),
        ('DEEP_PROCESSOR', 'ENDING_INVENTORY', 'INVENTORY', 630),
        ('WHOLESALE_MARKET', 'MKT_PURCHASE_BASE_PRICE', 'PRICE', 310),
        ('WHOLESALE_MARKET', 'PURCHASE_VOLUME', 'VOLUME', 410),
        ('WHOLESALE_MARKET', 'SALES_VOLUME', 'VOLUME', 420),
        ('WHOLESALE_MARKET', 'OPENING_INVENTORY', 'INVENTORY', 610),
        ('WHOLESALE_MARKET', 'STOCK_OUTFLOW', 'INVENTORY', 620),
        ('WHOLESALE_MARKET', 'ENDING_INVENTORY', 'INVENTORY', 630),
        ('RESERVE_ENTERPRISE', 'MKT_PURCHASE_BASE_PRICE', 'PRICE', 310),
        ('RESERVE_ENTERPRISE', 'PURCHASE_VOLUME', 'VOLUME', 410),
        ('RESERVE_ENTERPRISE', 'SALES_VOLUME', 'VOLUME', 420),
        ('RESERVE_ENTERPRISE', 'OPENING_INVENTORY', 'INVENTORY', 610),
        ('RESERVE_ENTERPRISE', 'STOCK_OUTFLOW', 'INVENTORY', 620),
        ('RESERVE_ENTERPRISE', 'ENDING_INVENTORY', 'INVENTORY', 630),
        ('RICE_MILL', 'MKT_PURCHASE_BASE_PRICE', 'PRICE', 310),
        ('RICE_MILL', 'PURCHASE_VOLUME', 'VOLUME', 410),
        ('RICE_MILL', 'PROCESSING_INPUT', 'PROCESSING', 510),
        ('RICE_MILL', 'MAIN_OUTPUT', 'PROCESSING', 520),
        ('RICE_MILL', 'BYPRODUCT_OUTPUT', 'PROCESSING', 530),
        ('RICE_MILL', 'PROCESSING_LOSS', 'PROCESSING', 540),
        ('RICE_MILL', 'ENDING_INVENTORY', 'INVENTORY', 630),
        ('BREEDING_FACTORY', 'MKT_PURCHASE_BASE_PRICE', 'PRICE', 310),
        ('BREEDING_FACTORY', 'PURCHASE_VOLUME', 'VOLUME', 410),
        ('BREEDING_FACTORY', 'ENDING_INVENTORY', 'INVENTORY', 630),
        ('FEED_MILL', 'MKT_PURCHASE_BASE_PRICE', 'PRICE', 310),
        ('FEED_MILL', 'PURCHASE_VOLUME', 'VOLUME', 410),
        ('FEED_MILL', 'PROCESSING_INPUT', 'PROCESSING', 510),
        ('FEED_MILL', 'ENDING_INVENTORY', 'INVENTORY', 630)
)
INSERT INTO platform.design_sample_field_applicability(
    contract_version, domain_code, product_code, object_type_code,
    field_code, group_code, sort_order)
SELECT context.contract_version, context.domain_code, context.product_code,
       context.object_type_code, field.field_code, field.group_code, field.sort_order
FROM platform.design_sample_context context
JOIN market_fields field ON field.object_type_code = context.object_type_code
WHERE context.domain_code = 'MARKET';

WITH market_quality_fields(product_code, field_code, sort_order) AS (
    VALUES
        ('CORN', 'MOISTURE', 710),
        ('CORN', 'TEST_WEIGHT', 720),
        ('CORN', 'IMPURITY', 730),
        ('CORN', 'IMPERFECT_GRAIN', 740),
        ('CORN', 'MILDEW', 750),
        ('SOYBEAN', 'PROTEIN', 710),
        ('SOYBEAN', 'OIL_YIELD', 720),
        ('SOYBEAN', 'IMPERFECT_GRAIN', 730),
        ('SOYBEAN', 'MOISTURE', 740),
        ('SOYBEAN', 'IMPURITY', 750),
        ('RICE', 'MOISTURE', 710),
        ('RICE', 'MILLING_YIELD', 720),
        ('RICE', 'BROWN_RICE_YIELD', 730),
        ('RICE', 'IMPURITY', 740)
)
INSERT INTO platform.design_sample_field_applicability(
    contract_version, domain_code, product_code, object_type_code,
    field_code, group_code, sort_order)
SELECT context.contract_version, context.domain_code, context.product_code,
       context.object_type_code, field.field_code, 'QUALITY', field.sort_order
FROM platform.design_sample_context context
JOIN market_quality_fields field ON field.product_code = context.product_code
WHERE context.domain_code = 'MARKET'
  AND context.object_type_code <> 'AGRICULTURAL_INPUT_STORE';

WITH agricultural_input_fields(field_code, sort_order) AS (
    VALUES
        ('AGRI_INPUT_SEED_SALES_VOLUME', 310),
        ('AGRI_INPUT_SEED_RETAIL_PRICE', 320),
        ('AGRI_INPUT_SUPPLY_STATUS', 330),
        ('AGRI_INPUT_PLANTING_INTENTION_TREND', 340)
)
INSERT INTO platform.design_sample_field_applicability(
    contract_version, domain_code, product_code, object_type_code,
    field_code, group_code, sort_order)
SELECT context.contract_version, context.domain_code, context.product_code,
       context.object_type_code, field.field_code, 'AGRICULTURAL_INPUT', field.sort_order
FROM platform.design_sample_context context
CROSS JOIN agricultural_input_fields field
WHERE context.domain_code = 'MARKET'
  AND context.object_type_code = 'AGRICULTURAL_INPUT_STORE';

INSERT INTO platform.design_sample_alias_definition(
    contract_version, alias_type, domain_code, alias_code, canonical_code)
VALUES
    ('design-sample-fields-v1', 'PRODUCT_LABEL', '', '稻谷', 'RICE'),
    ('design-sample-fields-v1', 'PRODUCT_SLUG', '', 'paddy', 'RICE'),
    ('design-sample-fields-v1', 'OBJECT_SLUG', 'PRODUCTION', 'farmer', 'FARMER'),
    ('design-sample-fields-v1', 'OBJECT_SLUG', 'PRODUCTION', 'village-committee', 'VILLAGE_COMMITTEE'),
    ('design-sample-fields-v1', 'OBJECT_SLUG', 'PRODUCTION', 'agri-station', 'AGRICULTURAL_TECH_STATION'),
    ('design-sample-fields-v1', 'OBJECT_SLUG', 'MARKET', 'trader', 'TRADER'),
    ('design-sample-fields-v1', 'OBJECT_SLUG', 'MARKET', 'deep-processor', 'DEEP_PROCESSOR'),
    ('design-sample-fields-v1', 'OBJECT_SLUG', 'MARKET', 'wholesale-market', 'WHOLESALE_MARKET'),
    ('design-sample-fields-v1', 'OBJECT_SLUG', 'MARKET', 'reserve-enterprise', 'RESERVE_ENTERPRISE'),
    ('design-sample-fields-v1', 'OBJECT_SLUG', 'MARKET', 'rice-mill', 'RICE_MILL'),
    ('design-sample-fields-v1', 'OBJECT_SLUG', 'MARKET', 'breeding-factory', 'BREEDING_FACTORY'),
    ('design-sample-fields-v1', 'OBJECT_SLUG', 'MARKET', 'feed-mill', 'FEED_MILL'),
    ('design-sample-fields-v1', 'OBJECT_SLUG', 'MARKET', 'agricultural-input-store', 'AGRICULTURAL_INPUT_STORE'),
    ('design-sample-fields-v1', 'OBJECT_LABEL', 'MARKET', '深加工', 'DEEP_PROCESSOR'),
    ('design-sample-fields-v1', 'OBJECT_LABEL', 'MARKET', '养殖厂', 'BREEDING_FACTORY');

CREATE FUNCTION platform.current_design_sample_contract_digest()
RETURNS varchar
LANGUAGE sql
STABLE
SET search_path = pg_catalog, platform
AS $$
    WITH active_contract AS (
        SELECT contract_version
        FROM platform.design_sample_contract
        WHERE active
    ), canonical_lines(section_order, row_order, line) AS (
        SELECT 10, contract_version,
               format('CONTRACT|%s|%s', contract_version, active)
        FROM platform.design_sample_contract contract
        JOIN active_contract USING (contract_version)
        UNION ALL
        SELECT 20, format('%08s|%s', sort_order, code),
               format('DOMAIN|%s|%s|%s|%s|%s', code, name, description, aliases, sort_order)
        FROM platform.design_sample_domain_definition
        JOIN active_contract USING (contract_version)
        UNION ALL
        SELECT 30, format('%08s|%s', sort_order, code),
               format('PRODUCT|%s|%s|%s|%s', code, name, aliases, sort_order)
        FROM platform.design_sample_product_definition
        JOIN active_contract USING (contract_version)
        UNION ALL
        SELECT 40, format('%s|%08s|%s', domain_code, sort_order, code),
               format('OBJECT|%s|%s|%s|%s|%s', domain_code, code, name, aliases, sort_order)
        FROM platform.design_sample_object_type_definition
        JOIN active_contract USING (contract_version)
        UNION ALL
        SELECT 50, format('%08s', sort_order),
               format('CONTEXT|%s|%s|%s|%s', domain_code, product_code, object_type_code, sort_order)
        FROM platform.design_sample_context
        JOIN active_contract USING (contract_version)
        UNION ALL
        SELECT 60, code,
               format('FIELD|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s',
                   code, section_code, label, description, value_type,
                   coalesce(numeric_precision::text, ''), coalesce(numeric_scale::text, ''),
                   coalesce(max_length::text, ''), coalesce(unit, ''), enum_options,
                   required, nullable, coalesce(default_value::text, ''), editable,
                   coalesce(minimum_value::text, ''), coalesce(maximum_value::text, ''), analysis_role)
        FROM platform.design_sample_field_definition
        JOIN active_contract USING (contract_version)
        UNION ALL
        SELECT 70,
               format('%s|%s|%s|%08s|%s', domain_code, product_code,
                      object_type_code, sort_order, field_code),
               format('APPLICABILITY|%s|%s|%s|%s|%s|%s', domain_code, product_code,
                      object_type_code, field_code, group_code, sort_order)
        FROM platform.design_sample_field_applicability
        JOIN active_contract USING (contract_version)
        UNION ALL
        SELECT 80, format('%s|%s|%s', alias_type, domain_code, alias_code),
               format('ALIAS|%s|%s|%s|%s', alias_type, domain_code, alias_code, canonical_code)
        FROM platform.design_sample_alias_definition
        JOIN active_contract USING (contract_version)
    )
    SELECT 'sha256:' || encode(
        sha256(convert_to(string_agg(line, E'\n' ORDER BY section_order, row_order), 'UTF8')),
        'hex')
    FROM canonical_lines
$$;

UPDATE platform.design_sample_contract
SET contract_digest = platform.current_design_sample_contract_digest()
WHERE active;

ALTER TABLE platform.design_sample_contract OWNER TO qiqihar_migration_owner;
ALTER TABLE platform.design_sample_domain_definition OWNER TO qiqihar_migration_owner;
ALTER TABLE platform.design_sample_product_definition OWNER TO qiqihar_migration_owner;
ALTER TABLE platform.design_sample_object_type_definition OWNER TO qiqihar_migration_owner;
ALTER TABLE platform.design_sample_context OWNER TO qiqihar_migration_owner;
ALTER TABLE platform.design_sample_field_definition OWNER TO qiqihar_migration_owner;
ALTER TABLE platform.design_sample_field_applicability OWNER TO qiqihar_migration_owner;
ALTER TABLE platform.design_sample_alias_definition OWNER TO qiqihar_migration_owner;
ALTER FUNCTION platform.current_design_sample_contract_digest() OWNER TO qiqihar_migration_owner;

REVOKE ALL ON TABLE
    platform.design_sample_contract,
    platform.design_sample_domain_definition,
    platform.design_sample_product_definition,
    platform.design_sample_object_type_definition,
    platform.design_sample_context,
    platform.design_sample_field_definition,
    platform.design_sample_field_applicability,
    platform.design_sample_alias_definition
FROM PUBLIC, qiqihar_enterprise_runtime;
REVOKE ALL ON FUNCTION platform.current_design_sample_contract_digest()
FROM PUBLIC, qiqihar_enterprise_runtime;

GRANT SELECT ON TABLE
    platform.design_sample_contract,
    platform.design_sample_domain_definition,
    platform.design_sample_product_definition,
    platform.design_sample_object_type_definition,
    platform.design_sample_context,
    platform.design_sample_field_definition,
    platform.design_sample_field_applicability,
    platform.design_sample_alias_definition
TO qiqihar_enterprise_runtime, CURRENT_USER;
GRANT EXECUTE ON FUNCTION platform.current_design_sample_contract_digest()
TO qiqihar_enterprise_runtime, CURRENT_USER;

COMMENT ON TABLE platform.design_sample_contract IS
    'Immutable version and canonical digest for the design-sample field metadata contract.';
COMMENT ON TABLE platform.design_sample_context IS
    'Exact legal domain, product and object-type combinations for design samples.';
COMMENT ON TABLE platform.design_sample_field_applicability IS
    'Fail-closed field applicability for one exact design-sample context.';
COMMENT ON FUNCTION platform.current_design_sample_contract_digest() IS
    'Computes SHA-256 from canonical ordered design-sample metadata; services compare it to the stored digest.';
