CREATE TABLE production.production_object_type_definition (
    code varchar(80) PRIMARY KEY,
    name varchar(100) NOT NULL UNIQUE,
    sort_order integer NOT NULL UNIQUE
);

INSERT INTO production.production_object_type_definition(code,name,sort_order) VALUES
    ('farmer','农户',10),
    ('village-committee','村委会',20),
    ('agri-station','农业技术推广站',30);

CREATE TABLE production.production_source_channel_definition (
    code varchar(80) PRIMARY KEY,
    name varchar(100) NOT NULL UNIQUE,
    sort_order integer NOT NULL UNIQUE
);

INSERT INTO production.production_source_channel_definition(code,name,sort_order) VALUES
    ('administrative-village-ledger','行政村台账',10),
    ('farmer-sample','农户样本',20),
    ('family-farm-sample','家庭农场样本',30),
    ('agricultural-station-observation','农技站观测',40),
    ('field-yield-survey','田间测产调查',50);

CREATE TABLE production.production_business_role_definition (
    code varchar(80) PRIMARY KEY,
    name varchar(100) NOT NULL UNIQUE,
    capability_template_version_id varchar(120) NOT NULL UNIQUE,
    sort_order integer NOT NULL UNIQUE,
    UNIQUE (code, capability_template_version_id)
);

INSERT INTO production.production_business_role_definition(
    code,name,capability_template_version_id,sort_order
) VALUES
    ('production-survey','产情调查对象','CAPABILITY-PRODUCTION-FULL-2',10),
    ('quality-sample','质量抽样对象','CAPABILITY-QUALITY-1',20),
    ('field-observation','田间观测对象','CAPABILITY-FIELD-1',30);

CREATE TABLE production.monitoring_object (
    object_id uuid PRIMARY KEY,
    object_name varchar(200) NOT NULL,
    object_type_code varchar(80) NOT NULL
        REFERENCES production.production_object_type_definition(code),
    region_code varchar(12) NOT NULL REFERENCES platform.region(code),
    source_channel_code varchar(80) NOT NULL
        REFERENCES production.production_source_channel_definition(code),
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

CREATE UNIQUE INDEX production_object_one_dossier_per_region_name
    ON production.monitoring_object(region_code, lower(btrim(object_name)));
CREATE INDEX production_object_by_region
    ON production.monitoring_object(region_code, validity_status, object_name);

CREATE TABLE production.monitoring_object_product (
    object_id uuid NOT NULL REFERENCES production.monitoring_object(object_id) ON DELETE CASCADE,
    product_code varchar(40) NOT NULL REFERENCES platform.product(code),
    PRIMARY KEY (object_id, product_code)
);

CREATE TABLE production.monitoring_object_cultivar (
    object_id uuid NOT NULL REFERENCES production.monitoring_object(object_id) ON DELETE CASCADE,
    cultivar_code varchar(60) NOT NULL REFERENCES platform.cultivar(code),
    PRIMARY KEY (object_id, cultivar_code)
);

CREATE TABLE production.monitoring_object_role_assignment (
    object_id uuid NOT NULL REFERENCES production.monitoring_object(object_id) ON DELETE CASCADE,
    role_code varchar(80) NOT NULL REFERENCES production.production_business_role_definition(code),
    effective_from date NOT NULL,
    effective_to date,
    capability_template_version_id varchar(120) NOT NULL,
    PRIMARY KEY (object_id, role_code),
    CHECK (effective_to IS NULL OR effective_to >= effective_from),
    FOREIGN KEY (role_code, capability_template_version_id)
        REFERENCES production.production_business_role_definition(code, capability_template_version_id)
);

CREATE TABLE production.monitoring_object_revision (
    revision_id uuid PRIMARY KEY,
    object_id uuid NOT NULL REFERENCES production.monitoring_object(object_id),
    object_version bigint NOT NULL,
    snapshot_json jsonb NOT NULL,
    recorded_at timestamptz NOT NULL,
    recorded_by varchar(120) NOT NULL REFERENCES platform.security_user(subject_id),
    UNIQUE (object_id, object_version)
);

CREATE OR REPLACE FUNCTION production.reject_monitoring_object_revision_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'production monitoring object revisions are append-only';
END;
$$;

CREATE TRIGGER production_monitoring_object_revision_no_update
BEFORE UPDATE OR DELETE ON production.monitoring_object_revision
FOR EACH ROW EXECUTE FUNCTION production.reject_monitoring_object_revision_mutation();

COMMENT ON TABLE production.monitoring_object IS
    'Current governed projection for production survey and field observation objects.';
COMMENT ON TABLE production.monitoring_object_revision IS
    'Append-only snapshot history for every accepted production monitoring object version.';
