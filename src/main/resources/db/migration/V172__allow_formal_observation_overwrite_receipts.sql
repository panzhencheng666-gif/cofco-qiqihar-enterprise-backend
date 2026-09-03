ALTER TABLE platform.formal_sample_observation
    DROP CONSTRAINT IF EXISTS formal_sample_observation_source_domain_source_record_id_key;

CREATE INDEX formal_sample_observation_source_record_idx
    ON platform.formal_sample_observation(source_domain,source_record_id,official_saved_at DESC);

COMMENT ON TABLE platform.formal_sample_observation IS
    '已有正式样本保存正式观测的不可变回执；同一业务周期覆盖事实后保留每次保存回执。';
