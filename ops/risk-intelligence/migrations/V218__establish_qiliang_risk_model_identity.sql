SET lock_timeout = '2s';
SET statement_timeout = '30s';

-- Stop the bootstrap identity from receiving new work while its lineage is
-- examined below.
UPDATE risk.ai_training_policy
SET enabled=false,updated_at=now()
WHERE model_id=(
    SELECT model_id FROM risk.ai_model WHERE model_code='risk-reasoning-llm-v1'
);

-- An unused DRAFT bootstrap row has no training lineage, so it can safely
-- become the dedicated identity in place.  This keeps the existing policy id
-- and avoids leaving a misleading placeholder in the operator UI.
UPDATE risk.ai_model
SET model_code='qiliang-risk-llm-v1',
    model_name='齐粮智研模型 QL-Risk-27B',
    base_model_reference='mlx-community/Qwen3.8-27B-4bit',
    purpose_definition='{
      "identity":"QL-Risk-27B",
      "displayName":"齐粮智研模型",
      "foundationModel":"Qwen3.8-27B",
      "foundationRole":"base_weights_only",
      "specialization":["grain_inventory","grain_market","supply","logistics","quality","operations","risk_early_warning"],
      "decision":"evidence_bound_independent_judgement",
      "advisoryOnly":true,
      "persistentOperationalMemory":true,
      "requiredOutputs":["supportingEvidence","contradictingEvidence","uncertainty","recommendedActions"]
    }'::jsonb,
    updated_by_subject='system:migration-v218',updated_at=now()
WHERE model_code='risk-reasoning-llm-v1' AND status_code='DRAFT'
  AND NOT EXISTS (
      SELECT 1 FROM risk.model_version version
      WHERE version.model_id=risk.ai_model.model_id
  );

-- If another installation already trained the bootstrap model, its immutable
-- lineage is retained and suspended; the Qiliang identity starts separately.
UPDATE risk.ai_model
SET status_code='SUSPENDED',updated_by_subject='system:migration-v218',updated_at=now()
WHERE model_code='risk-reasoning-llm-v1' AND status_code='ACTIVE';

INSERT INTO risk.ai_model(
    model_id,model_code,model_name,model_kind,domain_code,base_model_reference,
    purpose_definition,status_code,created_by_subject,updated_by_subject
) SELECT
    '21800000-0000-0000-0000-000000000001',
    'qiliang-risk-llm-v1',
    '齐粮智研模型 QL-Risk-27B',
    'DOMAIN_LLM','CROSS_DOMAIN','mlx-community/Qwen3.8-27B-4bit',
    '{
      "identity":"QL-Risk-27B",
      "displayName":"齐粮智研模型",
      "foundationModel":"Qwen3.8-27B",
      "foundationRole":"base_weights_only",
      "specialization":["grain_inventory","grain_market","supply","logistics","quality","operations","risk_early_warning"],
      "decision":"evidence_bound_independent_judgement",
      "advisoryOnly":true,
      "persistentOperationalMemory":true,
      "requiredOutputs":["supportingEvidence","contradictingEvidence","uncertainty","recommendedActions"]
    }'::jsonb,
    'DRAFT','system:migration-v218','system:migration-v218'
WHERE NOT EXISTS (
    SELECT 1 FROM risk.ai_model WHERE model_code='qiliang-risk-llm-v1'
);

UPDATE risk.ai_training_policy
SET scheduled_local_time='03:15:00',schedule_timezone='Asia/Shanghai',
    training_window_days=180,minimum_new_labels=50,
    automatic_candidate_enabled=true,auto_activation_enabled=true,
    enabled=false,updated_at=now()
WHERE model_id=(
    SELECT model_id FROM risk.ai_model WHERE model_code='qiliang-risk-llm-v1'
);

INSERT INTO risk.ai_training_policy(
    training_policy_id,model_id,scheduled_local_time,schedule_timezone,
    training_window_days,minimum_new_labels,automatic_candidate_enabled,
    auto_activation_enabled,enabled,approved_by_subject,approved_at,
    minimum_shadow_labels,minimum_shadow_hours,minimum_shadow_f1,
    maximum_f1_regression,rollback_f1_drop
) SELECT
    '21800000-0000-0000-0000-000000000011',
    '21800000-0000-0000-0000-000000000001',
    '03:15:00','Asia/Shanghai',180,50,true,true,false,
    'system:approved-risk-governance',now(),10,24,0.600000,0.020000,0.050000
WHERE NOT EXISTS (
    SELECT 1 FROM risk.ai_training_policy policy
    JOIN risk.ai_model model ON model.model_id=policy.model_id
    WHERE model.model_code='qiliang-risk-llm-v1'
);

COMMENT ON TABLE risk.ai_model IS
    'Governed model identities. QL-Risk-27B is the dedicated grain-risk model; Qwen3.8-27B is only its foundation weight source.';
