package com.cofco.qiqihar.riskintelligence.trainingnode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cofco.qiqihar.graintrade.risk.application.RiskTrainingClaim;
import com.cofco.qiqihar.graintrade.risk.application.RiskTrainingExample;
import com.cofco.qiqihar.graintrade.risk.application.RiskTrainingRepository;
import com.cofco.qiqihar.graintrade.risk.application.RiskTrainingSnapshot;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RemoteTrainingCoordinatorTest {
    private static final Instant NOW=Instant.parse("2026-09-21T02:30:00Z");
    private static final Duration LEASE=Duration.ofMinutes(35);

    @Test
    void freezesAndReturnsARealSnapshotForTheMacNode() {
        RiskTrainingRepository repository=mock(RiskTrainingRepository.class);
        RiskTrainingClaim claim=new RiskTrainingClaim(
                UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),
                "qiliang-risk-llm-v1","齐粮智研模型 QL-Risk-27B","DOMAIN_LLM","CROSS_DOMAIN",
                "mlx-community/Qwen3.8-27B-4bit",30,2,7L);
        RiskTrainingSnapshot snapshot=new RiskTrainingSnapshot(
                UUID.randomUUID(),"a".repeat(64),List.of(
                new RiskTrainingExample(UUID.randomUUID(),NOW.minusSeconds(7200),"风险",true),
                new RiskTrainingExample(UUID.randomUUID(),NOW.minusSeconds(3600),"正常",false)),1,1);
        UUID runId=UUID.randomUUID();
        when(repository.claimNextByKind(NOW,"training-node:mac-m5-max",LEASE,"DOMAIN_LLM"))
                .thenReturn(Optional.of(claim));
        when(repository.countNewLabelsSinceLastSuccessfulRun(claim,NOW)).thenReturn(2);
        when(repository.freezeTrainingSnapshot(claim,NOW)).thenReturn(snapshot);
        when(repository.nextModelVersion(claim.modelId())).thenReturn(3);
        when(repository.createTrainingRun(claim,snapshot,NOW,"external-lora-adapter-v1"))
                .thenReturn(runId);

        Optional<RemoteTrainingJob> result=coordinator(repository).claimNext("mac-m5-max");

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().trainingRunId()).isEqualTo(runId);
        assertThat(result.orElseThrow().dataSha256()).isEqualTo("a".repeat(64));
        assertThat(result.orElseThrow().examples()).hasSize(2);
        verify(repository).markRunRunning(runId,NOW);
    }

    @Test
    void completesOnlyThroughTheRepositoryOwnershipGate() {
        RiskTrainingRepository repository=mock(RiskTrainingRepository.class);
        UUID executionId=UUID.randomUUID();
        UUID runId=UUID.randomUUID();
        RemoteTrainingArtifact submission=new RemoteTrainingArtifact(
                "oss://risk-models/model/v3","b".repeat(64),
                Map.of("f1",0.82d),Map.of("positiveProbability",0.5d));
        when(repository.completeRemoteRun(executionId,runId,"training-node:mac-m5-max",3,
                submission.toArtifact(),NOW)).thenReturn(true);

        assertThat(coordinator(repository).complete(
                "mac-m5-max",executionId,runId,3,submission)).isTrue();
    }

    @Test
    void checksArtifactUploadAgainstTheSameExecutionLease() {
        RiskTrainingRepository repository=mock(RiskTrainingRepository.class);
        UUID executionId=UUID.randomUUID();
        UUID runId=UUID.randomUUID();
        when(repository.ownsRemoteLease(
                executionId,runId,"training-node:mac-m5-max",NOW)).thenReturn(true);

        assertThat(coordinator(repository).ownsLease(
                "mac-m5-max",executionId,runId)).isTrue();
    }

    private static RemoteTrainingCoordinator coordinator(RiskTrainingRepository repository) {
        return new RemoteTrainingCoordinator(repository,Clock.fixed(NOW,ZoneOffset.UTC),LEASE);
    }
}
