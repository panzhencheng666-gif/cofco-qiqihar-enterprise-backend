SET lock_timeout = '2s';
SET statement_timeout = '30s';

-- Keep the shipped bootstrap UUID and all its lineage intact. V218 can then
-- create a separate QL identity without re-enabling a shared guarded policy.
-- On installations already at V218 or later this is an intentional no-op.
UPDATE risk.ai_training_policy
SET enabled=false,auto_activation_enabled=false,updated_at=now()
WHERE model_id=(SELECT model_id FROM risk.ai_model WHERE model_code='risk-reasoning-llm-v1');

CREATE OR REPLACE FUNCTION risk.enforce_ai_model_transition()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='INSERT' THEN
        IF NEW.status_code<>'DRAFT' THEN
            RAISE EXCEPTION 'AI model identities must be created as DRAFT';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.status_code<>'DRAFT' AND ROW(
        OLD.model_id,OLD.model_code,OLD.model_kind,OLD.domain_code,OLD.isolation_scope,
        OLD.base_model_reference,OLD.purpose_definition,OLD.created_by_subject,OLD.created_at
    ) IS DISTINCT FROM ROW(
        NEW.model_id,NEW.model_code,NEW.model_kind,NEW.domain_code,NEW.isolation_scope,
        NEW.base_model_reference,NEW.purpose_definition,NEW.created_by_subject,NEW.created_at
    ) THEN
        RAISE EXCEPTION 'Active AI model identity and purpose are immutable';
    END IF;
    IF OLD.status_code=NEW.status_code THEN
        RETURN NEW;
    END IF;
    -- A disabled unused bootstrap may be suspended without claiming it was
    -- active or approved. All normal identity and lifecycle checks remain.
    IF OLD.status_code='DRAFT' AND NEW.status_code='SUSPENDED'
       AND OLD.model_id='21500000-0000-0000-0000-000000000002'::uuid
       AND OLD.model_code='risk-reasoning-llm-v1'
       AND NEW.model_id=OLD.model_id AND NEW.model_code=OLD.model_code
       AND NOT EXISTS (SELECT 1 FROM risk.model_version WHERE model_id=OLD.model_id)
       AND NOT EXISTS (SELECT 1 FROM risk.ai_training_policy
                       WHERE model_id=OLD.model_id AND (enabled OR auto_activation_enabled)) THEN
        RETURN NEW;
    END IF;
    IF OLD.status_code='DRAFT' AND NEW.status_code<>'ACTIVE'
       OR OLD.status_code='ACTIVE' AND NEW.status_code NOT IN ('SUSPENDED','RETIRED')
       OR OLD.status_code='SUSPENDED' AND NEW.status_code NOT IN ('ACTIVE','RETIRED')
       OR OLD.status_code='RETIRED' THEN
        RAISE EXCEPTION 'Invalid AI model lifecycle transition from % to %',
            OLD.status_code,NEW.status_code;
    END IF;
    RETURN NEW;
END
$$;


UPDATE risk.ai_model
SET status_code='SUSPENDED',updated_by_subject='system:migration-v217.1',updated_at=now()
WHERE model_code='risk-reasoning-llm-v1' AND status_code IN ('DRAFT','ACTIVE');
