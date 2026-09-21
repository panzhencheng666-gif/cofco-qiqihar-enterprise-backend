package com.cofco.qiqihar.graintrade.risk.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.cofco.qiqihar.graintrade.risk.application.LocalRiskClassifierTrainer;
import com.cofco.qiqihar.graintrade.risk.application.RiskTrainingJob;
import com.cofco.qiqihar.graintrade.testsupport.ProtectedTestDatabase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

class JdbcRiskTrainingRepositoryIntegrationTest {
    private static final ProtectedTestDatabase DATABASE=ProtectedTestDatabase.shared();

    @BeforeAll
    static void migrateAndSeed() throws Exception {
        DATABASE.flyway().migrate();
        try (Connection connection=DATABASE.openConnection();Statement statement=connection.createStatement()) {
            statement.execute("""
                    INSERT INTO risk.risk_rule_set_version(
                      rule_set_id,version,domain_code,rule_set_name,status_code,scope_definition,
                      rule_definition,definition_sha256,missing_data_policy,late_event_policy,
                      created_by_subject)
                    VALUES('21510000-0000-0000-0000-000000000001',1,'INVENTORY','训练测试规则',
                      'DRAFT','{}','{}',repeat('a',64),'MANUAL_REVIEW','MANUAL_REVIEW','test')
                    """);
            statement.execute("""
                    UPDATE risk.risk_rule_set_version SET status_code='REVIEW_PENDING'
                    WHERE rule_set_id='21510000-0000-0000-0000-000000000001'
                    """);
            statement.execute("""
                    UPDATE risk.risk_rule_set_version SET status_code='APPROVED',
                      approved_by_subject='reviewer',approved_at=TIMESTAMPTZ '2026-09-20 00:00:00+00'
                    WHERE rule_set_id='21510000-0000-0000-0000-000000000001'
                    """);
            statement.execute("""
                    UPDATE risk.risk_rule_set_version SET status_code='ACTIVE',
                      effective_from=TIMESTAMPTZ '2026-09-20 00:00:00+00'
                    WHERE rule_set_id='21510000-0000-0000-0000-000000000001'
                    """);
            for (int index=1;index<=10;index++) {
                boolean positive=index<=6;
                String assessmentId="21510000-0000-0000-0000-%012d".formatted(100+index);
                String feedbackId="21510000-0000-0000-0000-%012d".formatted(200+index);
                statement.execute("""
                        INSERT INTO risk.risk_assessment(
                          assessment_id,domain_code,subject_type,subject_id,rule_set_id,rule_set_version,
                          evaluation_mode,risk_level,reason_codes,evidence_snapshot,evaluated_at,
                          evaluation_duration_ms)
                        VALUES('%s','INVENTORY','WAREHOUSE','subject-%d',
                          '21510000-0000-0000-0000-000000000001',1,'RULE','%s',ARRAY['%s'],
                          '{"source":"governed-test","sequence":%d}',
                          TIMESTAMPTZ '2026-09-%02d 00:00:00+00',12)
                        """.formatted(assessmentId,index,positive?"HIGH":"NONE",
                                positive?"STOCK_MISMATCH":"NORMAL",index,index));
                statement.execute("""
                        INSERT INTO risk.risk_case_feedback(
                          feedback_id,assessment_id,conclusion_code,reason_code,disposition_note,
                          resolved_by_subject,resolved_at)
                        VALUES('%s','%s','%s','SOURCE_VERIFIED','已核对正式凭证','reviewer',
                          TIMESTAMPTZ '2026-09-%02d 01:00:00+00')
                        """.formatted(feedbackId,assessmentId,
                                positive?"CONFIRMED":"FALSE_POSITIVE",index));
            }
        }
    }

    @Test
    void manualRequestFreezesRealLabelsAndCreatesOnlyACandidate(@TempDir Path artifactRoot) throws Exception {
        ObjectMapper json=new ObjectMapper();
        JdbcClient jdbc=JdbcClient.create(DATABASE.dataSource());
        var repository=new JdbcRiskTrainingRepository(jdbc,json);
        Instant now=Instant.parse("2026-09-21T03:00:00Z");
        UUID modelId=UUID.fromString("21500000-0000-0000-0000-000000000001");

        UUID executionId=repository.enqueueManualExecution(modelId,"reviewer",now);
        var claim=repository.claimNext(now,"integration-worker",Duration.ofMinutes(5)).orElseThrow();
        var snapshot=repository.freezeTrainingSnapshot(claim,now);
        int version=repository.nextModelVersion(modelId);
        UUID runId=repository.createTrainingRun(claim,snapshot,now,"builtin-bernoulli-naive-bayes");
        repository.markRunRunning(runId,now);
        var artifact=new LocalRiskClassifierTrainer(json,artifactRoot).train(new RiskTrainingJob(
                modelId,claim.modelCode(),claim.domainCode(),version,snapshot.trainingSnapshotId(),
                claim.randomSeed(),snapshot.examples()));
        repository.completeRunAndCreateCandidate(executionId,runId,modelId,claim.domainCode(),
                version,artifact,now.plusSeconds(3));

        assertThat(snapshot.examples()).hasSize(10);
        assertThat(snapshot.positiveLabelCount()).isEqualTo(6);
        assertThat(snapshot.negativeLabelCount()).isEqualTo(4);
        assertThat(Files.isRegularFile(Path.of(artifact.artifactReference()))).isTrue();
        assertThat(jdbc.sql("SELECT status_code FROM risk.training_schedule_execution WHERE execution_id=:id")
                .param("id",executionId).query(String.class).single()).isEqualTo("SUCCEEDED");
        assertThat(jdbc.sql("SELECT status_code FROM risk.model_version WHERE model_id=:id AND version=:version")
                .param("id",modelId).param("version",version).query(String.class).single())
                .isEqualTo("CANDIDATE");
        assertThat(jdbc.sql("SELECT count(*) FROM risk.model_version WHERE model_id=:id AND status_code='ACTIVE'")
                .param("id",modelId).query(Integer.class).single()).isZero();

        Instant retryAt=now.plusSeconds(60);
        UUID abandonedExecution=repository.enqueueManualExecution(modelId,"reviewer",retryAt);
        var abandonedClaim=repository.claimNext(retryAt,"abandoned-worker",Duration.ofMinutes(5))
                .orElseThrow();
        var abandonedSnapshot=repository.freezeTrainingSnapshot(abandonedClaim,retryAt);
        UUID abandonedRun=repository.createTrainingRun(abandonedClaim,abandonedSnapshot,retryAt,
                "builtin-bernoulli-naive-bayes");
        repository.markRunRunning(abandonedRun,retryAt);

        assertThat(repository.failExpiredExecutions(retryAt.plus(Duration.ofMinutes(6)))).isEqualTo(1);
        assertThat(jdbc.sql("SELECT status_code FROM risk.training_schedule_execution WHERE execution_id=:id")
                .param("id",abandonedExecution).query(String.class).single()).isEqualTo("FAILED");
        assertThat(jdbc.sql("SELECT failure_code FROM risk.training_run WHERE training_run_id=:id")
                .param("id",abandonedRun).query(String.class).single())
                .isEqualTo("WORKER_LEASE_EXPIRED");
    }
}
