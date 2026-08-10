CREATE TABLE market.market_object_type_definition (
    code varchar(80) PRIMARY KEY,
    name varchar(100) NOT NULL UNIQUE,
    sort_order integer NOT NULL UNIQUE
);

INSERT INTO market.market_object_type_definition(code,name,sort_order) VALUES
    ('grain-trading-enterprise','粮食贸易企业',10),
    ('grain-processing-enterprise','粮食加工企业',20),
    ('breeding-farm','养殖企业',30),
    ('feed-mill','饲料企业',40),
    ('wholesale-market','批发市场',50),
    ('grain-storage-enterprise','粮食承储企业',60);

CREATE TABLE market.market_business_role_definition (
    code varchar(80) PRIMARY KEY,
    name varchar(100) NOT NULL UNIQUE,
    capability_template_version_id varchar(120) NOT NULL UNIQUE,
    sort_order integer NOT NULL UNIQUE,
    UNIQUE (code, capability_template_version_id)
);

INSERT INTO market.market_business_role_definition(
    code,name,capability_template_version_id,sort_order
) VALUES
    ('trader','贸易商','CAPABILITY-MARKET-trader',10),
    ('corn-processor','玉米深加工企业','CAPABILITY-MARKET-corn-processor',20),
    ('soy-crusher','大豆压榨企业','CAPABILITY-MARKET-soy-crusher',30),
    ('soy-protein','大豆蛋白加工企业','CAPABILITY-MARKET-soy-protein',40),
    ('food-condiment','食品和调味品企业','CAPABILITY-MARKET-food-condiment',50),
    ('rice-mill','米厂','CAPABILITY-MARKET-rice-mill',60),
    ('feed','饲料企业','CAPABILITY-MARKET-feed',70),
    ('livestock','养殖企业','CAPABILITY-MARKET-livestock',80),
    ('reserve','承储企业 / 储备库','CAPABILITY-MARKET-reserve',90),
    ('wholesale-market','批发市场','CAPABILITY-MARKET-wholesale-market',100);

CREATE TABLE market.market_source_channel_definition (
    code varchar(80) PRIMARY KEY,
    name varchar(100) NOT NULL UNIQUE,
    sort_order integer NOT NULL UNIQUE
);

INSERT INTO market.market_source_channel_definition(code,name,sort_order) VALUES
    ('enterprise-report','企业直报',10);

CREATE TABLE market.monitoring_object (
    object_id uuid PRIMARY KEY,
    object_name varchar(200) NOT NULL,
    object_type_code varchar(80) NOT NULL
        REFERENCES market.market_object_type_definition(code),
    region_code varchar(12) NOT NULL REFERENCES platform.region(code),
    source_channel_code varchar(80) NOT NULL
        REFERENCES market.market_source_channel_definition(code),
    responsible_subject_id varchar(120) NOT NULL
        REFERENCES platform.security_user(subject_id),
    responsible_person varchar(160) NOT NULL,
    effective_from date NOT NULL,
    effective_to date,
    validity_status varchar(20) NOT NULL
        CHECK (validity_status IN ('active','inactive')),
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by varchar(120) NOT NULL REFERENCES platform.security_user(subject_id),
    CHECK (effective_to IS NULL OR effective_to >= effective_from)
);

CREATE UNIQUE INDEX monitoring_object_one_dossier_per_region_name
    ON market.monitoring_object(region_code, lower(btrim(object_name)));
CREATE INDEX monitoring_object_by_region
    ON market.monitoring_object(region_code, validity_status, object_name);

CREATE TABLE market.monitoring_object_product (
    object_id uuid NOT NULL REFERENCES market.monitoring_object(object_id) ON DELETE CASCADE,
    product_code varchar(40) NOT NULL REFERENCES platform.product(code),
    PRIMARY KEY (object_id, product_code)
);

CREATE TABLE market.monitoring_object_cultivar (
    object_id uuid NOT NULL REFERENCES market.monitoring_object(object_id) ON DELETE CASCADE,
    cultivar_code varchar(60) NOT NULL REFERENCES platform.cultivar(code),
    PRIMARY KEY (object_id, cultivar_code)
);

CREATE TABLE market.monitoring_object_role_assignment (
    object_id uuid NOT NULL REFERENCES market.monitoring_object(object_id) ON DELETE CASCADE,
    role_code varchar(80) NOT NULL REFERENCES market.market_business_role_definition(code),
    effective_from date NOT NULL,
    effective_to date,
    capability_template_version_id varchar(120) NOT NULL,
    PRIMARY KEY (object_id, role_code),
    CHECK (effective_to IS NULL OR effective_to >= effective_from),
    FOREIGN KEY (role_code, capability_template_version_id)
        REFERENCES market.market_business_role_definition(code, capability_template_version_id)
);

CREATE TABLE market.monitoring_object_revision (
    revision_id uuid PRIMARY KEY,
    object_id uuid NOT NULL REFERENCES market.monitoring_object(object_id),
    object_version bigint NOT NULL,
    snapshot_json jsonb NOT NULL,
    recorded_at timestamptz NOT NULL,
    recorded_by varchar(120) NOT NULL REFERENCES platform.security_user(subject_id),
    UNIQUE (object_id, object_version)
);

CREATE OR REPLACE FUNCTION market.reject_monitoring_object_revision_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'market monitoring object revisions are append-only';
END;
$$;

CREATE TRIGGER monitoring_object_revision_no_update
BEFORE UPDATE OR DELETE ON market.monitoring_object_revision
FOR EACH ROW EXECUTE FUNCTION market.reject_monitoring_object_revision_mutation();

INSERT INTO platform.access_permission(code,name,active,sort_order)
SELECT 'MARKET_OBJECT_MANAGE','维护市场监测对象',true,coalesce(max(sort_order),0)+10
FROM platform.access_permission;

COMMENT ON TABLE market.monitoring_object IS
    'Current governed projection: one market subject dossier can carry multiple effective business roles.';
COMMENT ON TABLE market.monitoring_object_revision IS
    'Append-only snapshot history for every accepted market monitoring object version.';
