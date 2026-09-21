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
}
