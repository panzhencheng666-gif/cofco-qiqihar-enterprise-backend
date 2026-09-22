package com.cofco.qiqihar.graintrade.risk.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class RiskTrainingMigrationContractTest {
    @Test
    void migrationAddsImmutableExamplesAndIdempotentDailyExecutions() throws Exception {
        String sql=Files.readString(Path.of(
                "src/main/resources/db/migration/V215__operate_daily_risk_ai_training.sql"));

        assertThat(sql)
                .contains("CREATE TABLE risk.training_example")
                .contains("CREATE TABLE risk.training_schedule_execution")
                .contains("training_schedule_one_daily_execution")
                .contains("CHECK (NOT auto_activation_enabled)")
                .contains("'RISK_CLASSIFIER'")
                .contains("risk-domain-classifier-v1");
    }

    @Test
    void automaticPromotionMigrationRequiresRealShadowEvidenceAndRollbackLedger() throws Exception {
        String sql=Files.readString(Path.of(
                "src/main/resources/db/migration/V216__automate_risk_model_promotion.sql"));

        assertThat(sql)
                .contains("CREATE TABLE risk.model_live_prediction")
                .contains("CREATE TABLE risk.model_activation_event")
                .contains("minimum_shadow_labels")
                .contains("minimum_shadow_hours")
                .contains("auto_activation_enabled=true")
                .contains("'STANDBY'");
    }

    @Test
    void dedicatedQiliangModelHasNewIdentityAndPreservesBootstrapLineage() throws Exception {
        String sql=Files.readString(Path.of(
                "src/main/resources/db/migration/V218__establish_qiliang_risk_model_identity.sql"));

        assertThat(sql)
                .contains("qiliang-risk-llm-v1")
                .contains("齐粮智研模型 QL-Risk-27B")
                .contains("mlx-community/Qwen3.8-27B-4bit")
                .contains("foundationRole")
                .contains("SET enabled=false")
                .contains("model_code='risk-reasoning-llm-v1'")
                .contains("NOT EXISTS (\n      SELECT 1 FROM risk.model_version")
                .doesNotContain("DELETE FROM risk.ai_model")
                .doesNotContain("DELETE FROM risk.model_version");
    }

    @Test
    void hardeningMigrationSeparatesPreexistingBootstrapLineageAndResolvesPolicyByCode()
            throws Exception {
        String sql=Files.readString(Path.of(
                "src/main/resources/db/migration/V219__harden_qiliang_model_lineage.sql"));

        assertThat(sql)
                .contains("created_at<v218_installed_at")
                .contains("model_code='risk-reasoning-llm-v1'")
                .contains("model.model_code='qiliang-risk-llm-v1'")
                .contains("policy.model_id=model.model_id")
                .contains("Exactly one QL-Risk model identity is required")
                .doesNotContain("DELETE FROM risk.training_run")
                .doesNotContain("DELETE FROM risk.model_version");
    }

    @Test
    void expertQueueMigrationIsImmutableSeparateAndLeastPrivilege() throws Exception {
        String sql=Files.readString(Path.of(
                "src/main/resources/db/migration/V220__create_expert_sft_queue.sql"));

        assertThat(sql)
                .contains("CREATE TABLE risk.expert_dataset_snapshot")
                .contains("UNIQUE (dataset_id,dataset_sha256)")
                .contains("CREATE TABLE risk.expert_training_task")
                .contains("training_kind text NOT NULL DEFAULT 'EXPERT_SFT'")
                .contains("CREATE TABLE risk.expert_training_audit")
                .contains("FOR EACH ROW EXECUTE FUNCTION risk.reject_expert_immutable_change()")
                .contains("FOR EACH ROW EXECUTE FUNCTION risk.protect_expert_task_identity()")
                .contains("GRANT SELECT,INSERT,UPDATE ON risk.expert_training_task")
                .doesNotContain("GRANT DELETE")
                .doesNotContain("GRANT CREATE ON SCHEMA");
    }

    @Test
    void expertQueueRepairGrantsOnlyRequiredRiskRuntimePrivileges() throws Exception {
        String migration=Files.readString(Path.of(
                "src/main/resources/db/migration/V221__grant_expert_queue_risk_runtime.sql"));
        String roleSql=Files.readString(Path.of(
                "ops/risk-intelligence/create-risk-runtime-roles.sql"));

        assertThat(migration)
                .contains("CREATE ROLE qiqihar_risk_runtime NOLOGIN")
                .contains("GRANT USAGE ON SCHEMA risk TO qiqihar_risk_runtime")
                .contains("GRANT SELECT,INSERT ON risk.expert_dataset_snapshot TO qiqihar_risk_runtime")
                .contains("GRANT SELECT,INSERT,UPDATE ON risk.expert_training_task TO qiqihar_risk_runtime")
                .contains("GRANT SELECT,INSERT ON risk.expert_training_audit TO qiqihar_risk_runtime")
                .doesNotContain("GRANT DELETE")
                .doesNotContain("GRANT CREATE ON SCHEMA");
        assertThat(roleSql)
                .contains("risk.expert_dataset_snapshot")
                .contains("risk.expert_training_task")
                .contains("risk.expert_training_audit");
    }
}
