-- V216 enabled automatic activation for seeded risk models before an independent
-- reviewer and explicit promotion authority were established. Keep the model
-- lifecycle data intact, but fail closed for the two shipped policies.
UPDATE risk.ai_training_policy
SET auto_activation_enabled=false,updated_at=now()
WHERE model_id IN (
    '21500000-0000-0000-0000-000000000001'::uuid,
    '21500000-0000-0000-0000-000000000002'::uuid
) AND auto_activation_enabled;

ALTER TABLE risk.ai_training_policy
    ADD CONSTRAINT ai_training_policy_seed_auto_activation_disabled
    CHECK (
        model_id NOT IN (
            '21500000-0000-0000-0000-000000000001'::uuid,
            '21500000-0000-0000-0000-000000000002'::uuid
        ) OR NOT auto_activation_enabled
    );

COMMENT ON CONSTRAINT ai_training_policy_seed_auto_activation_disabled
    ON risk.ai_training_policy IS
    'Seeded models remain manual-promotion only until a later governed migration authorizes activation.';
