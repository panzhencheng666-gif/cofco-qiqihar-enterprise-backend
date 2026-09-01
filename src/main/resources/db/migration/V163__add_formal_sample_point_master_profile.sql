INSERT INTO platform.access_permission(code,name,active,sort_order)
VALUES('FORMAL_SAMPLE_MANAGE','维护正式样本',true,3240);

CREATE TABLE registry.formal_sample_point_profile (
    sample_point_id uuid PRIMARY KEY
        REFERENCES registry.sample_point(sample_point_id) ON DELETE CASCADE,
    object_type_code varchar(80) NOT NULL
        REFERENCES platform.object_type(code),
    address varchar(500) NOT NULL CHECK (btrim(address)<>''),
    created_by varchar(120) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by varchar(120) NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX formal_sample_point_profile_object_type
    ON registry.formal_sample_point_profile(object_type_code,sample_point_id);

GRANT SELECT,INSERT,UPDATE ON registry.formal_sample_point_profile
TO qiqihar_enterprise_runtime;
REVOKE DELETE,TRUNCATE,REFERENCES,TRIGGER
ON registry.formal_sample_point_profile FROM qiqihar_enterprise_runtime;

COMMENT ON TABLE registry.formal_sample_point_profile IS
    'One-to-one stable formal-sample attributes; period observations and annual-network membership remain separate.';
