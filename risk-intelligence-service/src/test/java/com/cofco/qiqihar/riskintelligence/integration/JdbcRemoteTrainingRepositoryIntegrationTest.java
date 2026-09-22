package com.cofco.qiqihar.riskintelligence.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cofco.qiqihar.graintrade.risk.application.RiskTrainingArtifact;
import com.cofco.qiqihar.graintrade.risk.application.RiskTrainingClaim;
import com.cofco.qiqihar.graintrade.risk.application.RiskTrainingRepository;
import com.cofco.qiqihar.riskintelligence.experttraining.ExpertTrainingRepository;
import com.cofco.qiqihar.riskintelligence.experttraining.ExpertDatasetValidator;
import com.cofco.qiqihar.graintrade.shared.application.ConflictException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfEnvironmentVariable(named = "RISK_DB_URL", matches = ".+")
class JdbcRemoteTrainingRepositoryIntegrationTest {
    @Autowired
    private RiskTrainingRepository repository;
    @Autowired
    private ExpertTrainingRepository expertRepository;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private TransactionTemplate transactions;

    @Test
    @Transactional
    void remoteLeaseQueriesExecuteAgainstTheRealRiskSchemaWithoutTouchingUnknownWork() {
        Instant now=Instant.parse("2026-09-21T03:00:00Z");
        UUID executionId=UUID.randomUUID();
        UUID runId=UUID.randomUUID();

        assertThat(repository.claimNextByKind(now,"integration-probe",Duration.ofMinutes(5),
                "__NO_SUCH_MODEL_KIND__")).isEmpty();
        RiskTrainingClaim probe=new RiskTrainingClaim(UUID.randomUUID(),UUID.randomUUID(),
                UUID.randomUUID(),"probe","probe","DOMAIN_LLM","__NO_SUCH_DOMAIN__",
                "probe",30,1,7L);
        assertThat(repository.countNewLabelsSinceLastSuccessfulRun(probe,now)).isGreaterThanOrEqualTo(0);
        assertThat(repository.renewRemoteLease(executionId,runId,"integration-probe",now,
                Duration.ofMinutes(5))).isFalse();
        assertThat(repository.ownsRemoteLease(
                executionId,runId,"integration-probe",now)).isFalse();
        assertThat(repository.completeRemoteRun(executionId,runId,"integration-probe",1,
                new RiskTrainingArtifact("risk-artifact://integration-probe","a".repeat(64),
                        Map.of("probe",1.0d),Map.of("probe",1.0d),
                        "integration-probe","1","RETRAIN"),now)).isFalse();
        assertThat(expertRepository.claim("integration-probe", now, Duration.ofMinutes(5)))
                .isEmpty();
        assertThat(expertRepository.heartbeat(UUID.randomUUID(), "integration-probe", now,
                Duration.ofMinutes(5))).isEmpty();
    }

    @Test
    void concurrentTransactionsCanAssignOneExpertTaskToOnlyOneOwner() throws Exception {
        Instant now=Instant.parse("2026-09-22T06:30:00Z");
        String suffix=UUID.randomUUID().toString();
        UUID taskId=transactions.execute(status -> {
            var dataset=json.readTree(syntheticDataset());
            ((tools.jackson.databind.node.ObjectNode) dataset).put("datasetId","concurrency-"+suffix);
            var snapshot=expertRepository.saveDataset(new ExpertDatasetValidator(json).validate(dataset),
                    "integration-root-"+suffix,now);
            var config=json.readTree("""
                    {"iterations":10,"maxSeqLength":1024,"numLayers":4,"seed":7,
                     "learningRate":0.00001}
                    """);
            return expertRepository.requestTask(snapshot.snapshotId(),"integration-root-"+suffix,
                    "concurrency-key",config,"9".repeat(64),"qiliang-risk-llm-v1",now).taskId();
        });

        var ready=new CountDownLatch(2);
        var start=new CountDownLatch(1);
        var claimsFinished=new CountDownLatch(2);
        var pool=Executors.newFixedThreadPool(2);
        try {
            var claims=List.of("concurrent-node-a","concurrent-node-b").stream()
                    .map(node -> pool.submit(() -> transactions.execute(status -> {
                        ready.countDown();
                        await(start);
                        Optional<ExpertTrainingRepository.Claim> claim=expertRepository.claim(
                                node,now,Duration.ofMinutes(5));
                        claimsFinished.countDown();
                        await(claimsFinished);
                        status.setRollbackOnly();
                        return claim;
                    }))).toList();
            ready.await();
            start.countDown();
            assertThat(claims.stream().map(future -> {
                try {
                    return future.get();
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            }).flatMap(Optional::stream).filter(claim -> claim.taskId().equals(taskId)))
                    .hasSize(1);
        } finally {
            pool.shutdownNow();
            transactions.executeWithoutResult(status -> expertRepository.cancel(taskId,
                    "integration-root-"+suffix,now));
        }
    }

    @Test
    @Transactional
    void syntheticExpertLifecycleCoversIdempotencyRecoveryOwnershipAndTerminalReplay() {
        Instant now=Instant.parse("2026-09-22T06:00:00Z");
        var validated=new ExpertDatasetValidator(json).validate(json.readTree(syntheticDataset()));
        var snapshot=expertRepository.saveDataset(validated,"integration-root",now);
        var config=json.readTree("""
                {"iterations":10,"maxSeqLength":1024,"numLayers":4,"seed":7,
                 "learningRate":0.00001}
                """);
        var first=expertRepository.requestTask(snapshot.snapshotId(),"integration-root",
                "integration-key-1",config,"d".repeat(64),"qiliang-risk-llm-v1",now);
        assertThat(expertRepository.requestTask(snapshot.snapshotId(),"integration-root",
                "integration-key-1",config,"d".repeat(64),"qiliang-risk-llm-v1",now).taskId())
                .isEqualTo(first.taskId());
        assertThatThrownBy(() -> expertRepository.requestTask(snapshot.snapshotId(),
                "integration-root","integration-key-1",config,"e".repeat(64),
                "qiliang-risk-llm-v1",now)).isInstanceOf(ConflictException.class);

        var claim=expertRepository.claim("node-a",now,Duration.ofMinutes(5)).orElseThrow();
        assertThat(claim.taskId()).isEqualTo(first.taskId());
        assertThat(expertRepository.claim("node-b",now,Duration.ofMinutes(5))).isEmpty();
        assertThat(expertRepository.heartbeat(first.taskId(),"node-b",now,
                Duration.ofMinutes(5))).isEmpty();
        assertThat(expertRepository.progress(first.taskId(),"node-a",40,
                "LOCAL_TRAINING",now)).isTrue();
        assertThat(expertRepository.progress(first.taskId(),"node-a",39,
                "LOCAL_TRAINING",now)).isFalse();
        String stagedHash="a".repeat(64);
        assertThat(expertRepository.recordUpload(first.taskId(),"node-a",
                "risk-artifact://sha256/"+stagedHash,stagedHash,now)).isTrue();
        jdbc.sql("UPDATE risk.expert_training_task SET lease_until=:expired WHERE task_id=:taskId")
                .param("expired",java.sql.Timestamp.from(now.minusSeconds(1)))
                .param("taskId",first.taskId()).update();
        var retry=expertRepository.claim("node-b",now,Duration.ofMinutes(5)).orElseThrow();
        assertThat(retry.taskId()).isEqualTo(first.taskId());
        assertThat(retry.runId()).isEqualTo(claim.runId()).isEqualTo(first.taskId().toString());
        assertThat(retry.attempt()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT progress_percent FROM risk.expert_training_task WHERE task_id=:id")
                .param("id",first.taskId()).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT uploaded_artifact_reference FROM risk.expert_training_task WHERE task_id=:id")
                .param("id",first.taskId()).query(String.class).optional()).isEmpty();
        assertThat(expertRepository.cancel(first.taskId(),"integration-root",now).status())
                .isEqualTo("CANCEL_REQUESTED");
        assertThat(expertRepository.acknowledgeCancelled(first.taskId(),"node-a",now)).isFalse();
        assertThat(expertRepository.acknowledgeCancelled(first.taskId(),"node-b",now)).isTrue();

        var second=expertRepository.requestTask(snapshot.snapshotId(),"integration-root",
                "integration-key-2",config,"e".repeat(64),"qiliang-risk-llm-v1",now);
        expertRepository.claim("node-a",now,Duration.ofMinutes(5)).orElseThrow();
        String successHash="b".repeat(64);
        String successReference="risk-artifact://sha256/"+successHash;
        assertThat(expertRepository.recordUpload(second.taskId(),"node-a",
                successReference,successHash,now)).isTrue();
        var metrics=json.readTree("{\"loss\":0.1}");
        assertThat(expertRepository.complete(second.taskId(),"node-a",successReference,
                successHash,metrics,now)).isTrue();
        assertThat(expertRepository.complete(second.taskId(),"node-a",successReference,
                successHash,metrics,now)).isTrue();
        assertThat(expertRepository.complete(second.taskId(),"node-a",successReference,
                "c".repeat(64),metrics,now)).isFalse();

        var third=expertRepository.requestTask(snapshot.snapshotId(),"integration-root",
                "integration-key-3",config,"f".repeat(64),"qiliang-risk-llm-v1",now);
        expertRepository.claim("node-a",now,Duration.ofMinutes(5)).orElseThrow();
        assertThat(expertRepository.fail(third.taskId(),"node-b","LOCAL_TRAINING_FAILED",
                "synthetic failure",now)).isFalse();
        assertThat(expertRepository.fail(third.taskId(),"node-a","LOCAL_TRAINING_FAILED",
                "synthetic failure",now)).isTrue();
        assertThat(expertRepository.fail(third.taskId(),"node-a","LOCAL_TRAINING_FAILED",
                "synthetic failure",now)).isTrue();
        assertThat(expertRepository.fail(third.taskId(),"node-a","OUT_OF_MEMORY",
                "different",now)).isFalse();
    }

    private static String syntheticDataset() {
        return """
                {"schemaVersion":1,"datasetId":"integration-synthetic","sources":[
                  {"sourceId":"train-source","title":"Synthetic train","url":"https://example.test/train","license":"OWNED","licenseEvidenceUrl":"https://example.test/license/train","contentSha256":"%s","usage":"TRAINING_ALLOWED"},
                  {"sourceId":"valid-source","title":"Synthetic valid","url":"https://example.test/valid","license":"OWNED","licenseEvidenceUrl":"https://example.test/license/valid","contentSha256":"%s","usage":"TRAINING_ALLOWED"},
                  {"sourceId":"test-source","title":"Synthetic test","url":"https://example.test/test","license":"OWNED","licenseEvidenceUrl":"https://example.test/license/test","contentSha256":"%s","usage":"TRAINING_ALLOWED"}],
                 "examples":[
                  {"exampleId":"train-example","sourceIds":["train-source"],"groupId":"train-group","split":"train","question":"Synthetic train?","context":"Synthetic train context.","answer":"Synthetic train answer.","origin":"SYNTHETIC","verification":{"status":"VERIFIED","reference":"synthetic"}},
                  {"exampleId":"valid-example","sourceIds":["valid-source"],"groupId":"valid-group","split":"valid","question":"Synthetic valid?","context":"Synthetic valid context.","answer":"Synthetic valid answer.","origin":"SYNTHETIC","verification":{"status":"VERIFIED","reference":"synthetic"}},
                  {"exampleId":"test-example","sourceIds":["test-source"],"groupId":"test-group","split":"test","question":"Synthetic test?","context":"Synthetic test context.","answer":"Synthetic test answer.","origin":"SYNTHETIC","verification":{"status":"VERIFIED","reference":"synthetic"}}]}
                """.formatted("a".repeat(64),"b".repeat(64),"c".repeat(64));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
