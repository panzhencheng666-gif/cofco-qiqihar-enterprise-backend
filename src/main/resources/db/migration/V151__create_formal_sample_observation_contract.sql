CREATE TABLE platform.formal_sample_observation (
    observation_id uuid PRIMARY KEY,
    source_domain varchar(30) NOT NULL
        CHECK (source_domain IN ('PRODUCTION','MARKET','LOGISTICS')),
    source_record_id varchar(120) NOT NULL,
    sample_point_id uuid NOT NULL REFERENCES registry.sample_point(sample_point_id),
    product_code varchar(40) NOT NULL REFERENCES platform.product(code),
    observed_at timestamptz NOT NULL,
    official_saved_at timestamptz NOT NULL,
    actor_subject_id varchar(120) NOT NULL REFERENCES platform.security_user(subject_id),
    idempotency_key varchar(160) NOT NULL,
    request_sha256 char(64) NOT NULL CHECK (request_sha256 ~ '^[0-9a-f]{64}$'),
    projection_version varchar(96) NOT NULL,
    response_json jsonb NOT NULL,
    UNIQUE(actor_subject_id,source_domain,idempotency_key),
    UNIQUE(source_domain,source_record_id),
    CHECK (length(btrim(idempotency_key)) BETWEEN 8 AND 160),
    CHECK (jsonb_typeof(response_json)='object')
);

CREATE INDEX formal_sample_observation_history_idx
    ON platform.formal_sample_observation(
      sample_point_id,source_domain,product_code,observed_at DESC,official_saved_at DESC);

ALTER TABLE platform.formal_sample_observation OWNER TO qiqihar_migration_owner;
GRANT SELECT,INSERT ON TABLE platform.formal_sample_observation TO qiqihar_enterprise_runtime;

COMMENT ON TABLE platform.formal_sample_observation IS
    '已有正式样本的一次保存正式观测收据；领域事实仍存放在各业务正式表。';
