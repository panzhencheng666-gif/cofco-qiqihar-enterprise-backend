package com.cofco.qiqihar.graintrade.risk.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class RiskFoundationMigrationContractTest {
    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V214__create_inventory_risk_foundation.sql");

    @Test
    void addsAnIsolatedFoundationWithoutChangingExistingBusinessTables() throws IOException {
        String sql = Files.readString(MIGRATION);

        assertThat(sql)
                .contains("CREATE SCHEMA risk")
                .contains("CREATE TABLE risk.inventory_source")
                .contains("CREATE TABLE risk.inventory_source_event")
                .contains("CREATE TABLE risk.inventory_movement")
                .contains("CREATE TABLE risk.inventory_balance")
                .contains("CREATE TABLE risk.risk_rule_set_version")
                .contains("CREATE TABLE risk.ai_model")
                .contains("CREATE TABLE risk.ai_training_policy")
                .contains("CREATE TABLE risk.knowledge_snapshot")
                .contains("CREATE TABLE risk.training_snapshot")
                .contains("CREATE TABLE risk.training_run")
                .contains("CREATE TABLE risk.model_version")
                .contains("CREATE TABLE risk.ai_judgement")
                .doesNotContain("ALTER TABLE overview.storage_facility")
                .doesNotContain("UPDATE overview.")
                .doesNotContain("DELETE FROM overview.");
    }

    @Test
    void preservesSourceEvidenceAndMakesInventoryPostingIdempotentAndImmutable() throws IOException {
        String sql = Files.readString(MIGRATION);

        assertThat(sql)
                .contains("UNIQUE (source_code, external_event_id)")
                .contains("UNIQUE (source_code, source_sequence)")
                .contains("original_quantity numeric(24,6)")
                .contains("original_unit varchar(20)")
                .contains("standard_quantity_tonnes numeric(24,6)")
                .contains("conversion_rule_version varchar(40)")
                .contains("payload_sha256 char(64)")
                .contains("CREATE FUNCTION risk.reject_inventory_movement_mutation()")
                .contains("BEFORE UPDATE OR DELETE ON risk.inventory_movement")
                .contains("CREATE FUNCTION risk.protect_inventory_source_event_evidence()")
                .contains("Inventory source evidence is immutable")
                .contains("CREATE FUNCTION risk.validate_inventory_movement()")
                .contains("REVERSAL must exactly negate the referenced movement")
                .contains("TRANSFER must contain one balanced SOURCE/TARGET pair")
                .doesNotContain("secret", "password", "private_key");
    }

    @Test
    void recordsDailyTrainingLineageAndRequiresGovernedModelPromotion() throws IOException {
        String sql = Files.readString(MIGRATION);

        assertThat(sql)
                .contains("snapshot_date date NOT NULL")
                .contains("data_sha256 char(64) NOT NULL")
                .contains("frequency_code varchar(20) NOT NULL DEFAULT 'DAILY'")
                .contains("auto_activation_enabled boolean NOT NULL DEFAULT false")
                .contains("CHECK (NOT auto_activation_enabled)")
                .contains("training_snapshot_id uuid NOT NULL")
                .contains("REFERENCES risk.training_snapshot(training_snapshot_id,domain_code)")
                .contains("status_code IN ('CANDIDATE','SHADOW','APPROVED','ACTIVE','REJECTED','RETIRED')")
                .contains("approved_by_subject varchar(160)")
                .contains("CREATE UNIQUE INDEX model_version_one_active_per_model")
                .contains("WHERE status_code='ACTIVE'")
                .contains("CREATE FUNCTION risk.enforce_model_version_transition()")
                .contains("Model versions must be created as CANDIDATE")
                .contains("Model candidates require a SUCCEEDED training run")
                .contains("Model candidates cannot prefill lifecycle evidence")
                .contains("Model approval requires a passed recorded evaluation")
                .contains("CREATE FUNCTION risk.validate_model_evaluation()")
                .contains("Model evaluations can only be recorded during SHADOW")
                .contains("Recorded model lifecycle evidence is immutable")
                .contains("Model identity, lineage and artifacts are immutable")
                .contains("CANDIDATE models must enter SHADOW before approval")
                .contains("Only APPROVED models can become ACTIVE")
                .contains("CREATE FUNCTION risk.enforce_training_run_transition()")
                .contains("Training requires an active model and accepted frozen snapshots")
                .contains("Training identity, inputs, code and parameters are immutable")
                .contains("CREATE FUNCTION risk.enforce_rule_set_version_transition()")
                .contains("Reviewed rule definitions are immutable; create a new version");
    }

    @Test
    void givesTheRiskSystemAnIndependentAdvisoryAiWithEvidenceBoundJudgements() throws IOException {
        String sql = Files.readString(MIGRATION);

        assertThat(sql)
                .contains("isolation_scope varchar(30) NOT NULL DEFAULT 'RISK_SYSTEM'")
                .contains("model_kind varchar(30) NOT NULL")
                .contains("base_model_reference text NOT NULL")
                .contains("knowledge_snapshot_id uuid")
                .contains("independent_conclusion text NOT NULL")
                .contains("supporting_evidence jsonb NOT NULL")
                .contains("contradicting_evidence jsonb NOT NULL")
                .contains("uncertainty_definition jsonb NOT NULL")
                .contains("advisory_only boolean NOT NULL DEFAULT true")
                .contains("CHECK (advisory_only)")
                .doesNotContain("consciousness", "self_aware", "autonomous_action");
    }
}
