SET lock_timeout = '2s';
SET statement_timeout = '30s';

-- V218 was already released before this hardening migration.  On an upgrade
-- where the bootstrap identity had pre-existing lineage, restore that identity
-- and create QL-Risk separately so historical runs are never re-attributed.
DO $$
DECLARE
    qiliang_id uuid;
    v218_installed_at timestamptz;
    has_legacy_lineage boolean;
BEGIN
    SELECT model_id INTO STRICT qiliang_id
    FROM risk.ai_model WHERE model_code='qiliang-risk-llm-v1';

    SELECT installed_on INTO STRICT v218_installed_at
    FROM public.risk_flyway_schema_history
    WHERE version='218' AND success
    ORDER BY installed_rank DESC LIMIT 1;

    SELECT EXISTS (
        SELECT 1 FROM risk.training_run
        WHERE model_id=qiliang_id AND created_at<v218_installed_at
        UNION ALL
        SELECT 1 FROM risk.model_version
        WHERE model_id=qiliang_id AND created_at<v218_installed_at
        UNION ALL
        SELECT 1 FROM risk.training_schedule_execution
        WHERE model_id=qiliang_id AND created_at<v218_installed_at
    ) INTO has_legacy_lineage;

    IF has_legacy_lineage THEN
        IF EXISTS (
            SELECT 1 FROM risk.training_schedule_execution
            WHERE model_id=qiliang_id AND created_at>=v218_installed_at
        ) THEN
            RAISE EXCEPTION 'Cannot split QL-Risk identity after mixed legacy and post-V218 executions';
        END IF;

        UPDATE risk.ai_training_policy
        SET enabled=false,updated_at=now()
        WHERE model_id=qiliang_id;

        UPDATE risk.ai_model
        SET model_code='risk-reasoning-llm-v1',
            model_name='风险研判独立大模型',
            base_model_reference='openai-compatible://configure-risk-domain-base-model',
            purpose_definition='{
              "decision":"evidence_bound_independent_judgement",
              "advisoryOnly":true,
              "requiredOutputs":["supportingEvidence","contradictingEvidence","uncertainty","recommendedActions"]
            }'::jsonb,
            status_code='SUSPENDED',updated_by_subject='system:migration-v219',updated_at=now()
        WHERE model_id=qiliang_id;

        INSERT INTO risk.ai_model(
            model_id,model_code,model_name,model_kind,domain_code,base_model_reference,
            purpose_definition,status_code,created_by_subject,updated_by_subject
        ) VALUES (
            '21900000-0000-0000-0000-000000000001',
            'qiliang-risk-llm-v1','齐粮智研模型 QL-Risk-27B','DOMAIN_LLM','CROSS_DOMAIN',
            'mlx-community/Qwen3.8-27B-4bit','{
              "identity":"QL-Risk-27B",
              "displayName":"齐粮智研模型",
              "foundationModel":"Qwen3.8-27B",
              "foundationRole":"base_weights_only",
              "specialization":["grain_inventory","grain_market","supply","logistics","quality","operations","risk_early_warning"],
              "decision":"evidence_bound_independent_judgement",
              "advisoryOnly":true,
              "persistentOperationalMemory":true,
              "requiredOutputs":["supportingEvidence","contradictingEvidence","uncertainty","recommendedActions"]
            }'::jsonb,'DRAFT','system:migration-v219','system:migration-v219'
        );
    END IF;
END
$$;

-- Resolve the policy through the model code rather than assuming a fixed UUID.
INSERT INTO risk.ai_training_policy(
    training_policy_id,model_id,scheduled_local_time,schedule_timezone,
    training_window_days,minimum_new_labels,automatic_candidate_enabled,
    auto_activation_enabled,enabled,approved_by_subject,approved_at,
    minimum_shadow_labels,minimum_shadow_hours,minimum_shadow_f1,
    maximum_f1_regression,rollback_f1_drop
) SELECT
    '21900000-0000-0000-0000-000000000011',model_id,
    '03:15:00','Asia/Shanghai',180,50,true,true,false,
    'system:approved-risk-governance',now(),10,24,0.600000,0.020000,0.050000
FROM risk.ai_model model
WHERE model.model_code='qiliang-risk-llm-v1'
  AND NOT EXISTS (
      SELECT 1 FROM risk.ai_training_policy policy WHERE policy.model_id=model.model_id
  );

UPDATE risk.ai_training_policy policy
SET scheduled_local_time='03:15:00',schedule_timezone='Asia/Shanghai',
    training_window_days=180,minimum_new_labels=50,
    automatic_candidate_enabled=true,auto_activation_enabled=true,
    updated_at=now()
WHERE policy.model_id=(
    SELECT model_id FROM risk.ai_model WHERE model_code='qiliang-risk-llm-v1'
);

DO $$
BEGIN
    IF (SELECT count(*) FROM risk.ai_model WHERE model_code='qiliang-risk-llm-v1')<>1 THEN
        RAISE EXCEPTION 'Exactly one QL-Risk model identity is required';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM risk.ai_training_policy policy
        JOIN risk.ai_model model ON model.model_id=policy.model_id
        WHERE model.model_code='qiliang-risk-llm-v1'
          AND policy.auto_activation_enabled
    ) THEN
        RAISE EXCEPTION 'QL-Risk policy was not resolved to its actual model identity';
    END IF;
END
$$;

COMMENT ON TABLE risk.training_run IS
    'Immutable training lineage; V219 preserves any pre-QL bootstrap runs under their original model identity.';
