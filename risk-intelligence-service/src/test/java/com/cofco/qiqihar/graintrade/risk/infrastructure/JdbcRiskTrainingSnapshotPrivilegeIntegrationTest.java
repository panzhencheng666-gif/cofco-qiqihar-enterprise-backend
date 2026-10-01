package com.cofco.qiqihar.graintrade.risk.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.cofco.qiqihar.graintrade.risk.application.RiskTrainingClaim;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import tools.jackson.databind.ObjectMapper;

@EnabledIfEnvironmentVariable(named="RISK_TEST_DB_URL",matches=".+")
class JdbcRiskTrainingSnapshotPrivilegeIntegrationTest {
    @Test
    void frozenSnapshotCanBeReusedWithTheProductionStyleInsertOnlyRole() throws Exception {
        String url=System.getenv("RISK_TEST_DB_URL");
        try (var connection=DriverManager.getConnection(url,
                System.getenv("RISK_TEST_DB_USERNAME"),
                System.getenv().getOrDefault("RISK_TEST_DB_PASSWORD",""));
                Statement statement=connection.createStatement()) {
            assertThat(connection.getCatalog()).isEqualTo("qiqihar_enterprise_test");
            statement.execute("SET ROLE qiqihar_enterprise_runtime");
            JdbcClient jdbc=JdbcClient.create(new SingleConnectionDataSource(connection,true));
            assertThat(jdbc.sql("SELECT has_table_privilege(current_user, 'risk.training_snapshot', 'UPDATE')")
                    .query(Boolean.class).single()).isFalse();

            var repository=new JdbcRiskTrainingRepository(jdbc,new ObjectMapper());
            var claim=new RiskTrainingClaim(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),
                    "risk-domain-classifier-v1","风险案例领域分类模型","RISK_CLASSIFIER",
                    "CROSS_DOMAIN","builtin://bernoulli-naive-bayes/v1",30,10,42);
            Instant cutoff=Instant.parse("2026-09-25T03:00:00Z");
            var first=repository.freezeTrainingSnapshot(claim,cutoff);
            var retry=repository.freezeTrainingSnapshot(claim,cutoff);

            assertThat(retry.trainingSnapshotId()).isEqualTo(first.trainingSnapshotId());
            assertThat(jdbc.sql("SELECT count(*) FROM risk.training_snapshot WHERE training_snapshot_id=:id")
                    .param("id",first.trainingSnapshotId()).query(Integer.class).single()).isEqualTo(1);
        }
    }
}
