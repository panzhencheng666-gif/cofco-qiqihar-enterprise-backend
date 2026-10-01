package com.cofco.qiqihar.riskintelligence.operations;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class RiskMigrationRunnerContractTest {
    @Test
    void rejectsSharedFlywayOwnershipThroughTheAssistantMigration() throws Exception {
        String source=Files.readString(Path.of(
                "src/main/java/com/cofco/qiqihar/riskintelligence/operations/RiskMigrationRunner.java"));

        assertThat(source).contains("requireSharedMigrationOwnership");
        assertThat(source).contains("V222__disable_unreviewed_risk_model_auto_activation.sql");
        assertThat(source).contains("!\"226\".equals(finalVersion)");
        assertThat(source).contains("V223__govern_ai_knowledge_snapshots.sql");
        assertThat(source).contains("V224__project_formal_market_facts_to_risk.sql");
        assertThat(source).contains("V225__track_market_rule_assessments.sql");
    }
}
