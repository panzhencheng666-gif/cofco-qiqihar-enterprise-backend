SET lock_timeout = '2s';
SET statement_timeout = '30s';

-- Original risk migration checksums are immutable. Forward reconciliation keeps
-- automated candidates available while requiring a governed manual promotion.
UPDATE risk.ai_training_policy
SET auto_activation_enabled=false,updated_at=now()
WHERE model_id IN (
    SELECT model_id FROM risk.ai_model WHERE model_code='qiliang-risk-llm-v1'
) OR model_id IN (
    '21500000-0000-0000-0000-000000000001'::uuid,
    '21500000-0000-0000-0000-000000000002'::uuid
);

ALTER TABLE risk.ai_training_policy
ADD CONSTRAINT risk_seed_manual_promotion_guard CHECK (
    model_id NOT IN (
        '21500000-0000-0000-0000-000000000001'::uuid,
        '21500000-0000-0000-0000-000000000002'::uuid
    ) OR NOT auto_activation_enabled
);

CREATE FUNCTION risk.reject_unreviewed_qiliang_auto_activation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.auto_activation_enabled AND EXISTS (
        SELECT 1 FROM risk.ai_model model
        WHERE model.model_id=NEW.model_id AND model.model_code='qiliang-risk-llm-v1'
    ) THEN
        RAISE EXCEPTION 'QL-Risk candidates require governed manual promotion';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER qiliang_manual_promotion_guard
BEFORE INSERT OR UPDATE ON risk.ai_training_policy
FOR EACH ROW EXECUTE FUNCTION risk.reject_unreviewed_qiliang_auto_activation();
