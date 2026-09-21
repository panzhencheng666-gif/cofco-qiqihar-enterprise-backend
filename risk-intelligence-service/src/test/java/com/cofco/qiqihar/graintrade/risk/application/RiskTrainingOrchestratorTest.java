package com.cofco.qiqihar.graintrade.risk.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import tools.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RiskTrainingOrchestratorTest {
    private static final Instant NOW=Instant.parse("2026-09-21T02:30:00Z");

    @Test
    void skipsWithoutCreatingFakeTrainingWhenGovernedLabelsAreInsufficient() throws Exception {
        RiskTrainingRepository repository=mock(RiskTrainingRepository.class);
        RiskModelTrainer trainer=mock(RiskModelTrainer.class);
        RiskTrainingClaim claim=claim(10);
        when(trainer.supports("RISK_CLASSIFIER")).thenReturn(true);
        when(repository.claimNext(NOW,"worker-1",Duration.ofMinutes(5))).thenReturn(Optional.of(claim));
        when(repository.countNewLabelsSinceLastSuccessfulRun(claim,NOW)).thenReturn(2);
        var service=service(repository,trainer);

        assertThat(service.processNext("worker-1")).isTrue();

        verify(repository).skipExecution(claim.executionId(),"INSUFFICIENT_NEW_LABELS",
                "上次成功训练后新增监督标签 2 条，低于策略门槛 10 条",NOW);
        verify(repository,never()).freezeTrainingSnapshot(any(),any());
        verify(trainer,never()).train(any());
        verify(repository,never()).createTrainingRun(any(),any(),any(),any());
    }

    @Test
    void persistsSucceededRunAndCandidateOnlyAfterARealArtifactExists() throws Exception {
        RiskTrainingRepository repository=mock(RiskTrainingRepository.class);
        RiskModelTrainer trainer=mock(RiskModelTrainer.class);
        RiskTrainingClaim claim=claim(2);
        when(trainer.supports("RISK_CLASSIFIER")).thenReturn(true);
        RiskTrainingSnapshot snapshot=snapshot(2);
        UUID runId=UUID.randomUUID();
        when(repository.claimNext(NOW,"worker-1",Duration.ofMinutes(5))).thenReturn(Optional.of(claim));
        when(repository.countNewLabelsSinceLastSuccessfulRun(claim,NOW)).thenReturn(2);
        when(repository.freezeTrainingSnapshot(claim,NOW)).thenReturn(snapshot);
        when(repository.createTrainingRun(claim,snapshot,NOW,"builtin-bernoulli-naive-bayes"))
                .thenReturn(runId);
        var artifact=new RiskTrainingArtifact("/tmp/model.json","a".repeat(64),
                Map.of("f1",0.75d),Map.of("positiveProbability",0.5d),
                "builtin-bernoulli-naive-bayes","1","RETRAIN");
        when(trainer.train(any())).thenReturn(artifact);
        when(repository.nextModelVersion(claim.modelId())).thenReturn(3);
        var service=service(repository,trainer);

        assertThat(service.processNext("worker-1")).isTrue();

        verify(repository).markRunRunning(runId,NOW);
        verify(repository).completeRunAndCreateCandidate(
                claim.executionId(),runId,claim.modelId(),claim.domainCode(),3,artifact,NOW);
    }

    @Test
    void claimsOnlyTheRequestedModelKindForARestrictedWorker() {
        RiskTrainingRepository repository=mock(RiskTrainingRepository.class);
        RiskModelTrainer trainer=mock(RiskModelTrainer.class);
        when(repository.claimNextByKind(NOW,"worker-1",Duration.ofMinutes(5),
                "RISK_CLASSIFIER")).thenReturn(Optional.empty());
        var service=service(repository,trainer);

        assertThat(service.processNext("worker-1","RISK_CLASSIFIER")).isFalse();

        verify(repository).claimNextByKind(NOW,"worker-1",Duration.ofMinutes(5),
                "RISK_CLASSIFIER");
        verify(repository,never()).claimNext(any(),any(),any());
    }

    private static RiskTrainingOrchestrator service(
            RiskTrainingRepository repository,RiskModelTrainer trainer) {
        return new RiskTrainingOrchestrator(repository,trainer,new ObjectMapper(),
                Clock.fixed(NOW,ZoneOffset.UTC),Duration.ofMinutes(5));
    }

    private static RiskTrainingClaim claim(int minimumLabels) {
        return new RiskTrainingClaim(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),
                "risk-domain-classifier-v1","领域风险模型","RISK_CLASSIFIER","CROSS_DOMAIN",
                "builtin://bernoulli-naive-bayes/v1",30,minimumLabels,7L);
    }

    private static RiskTrainingSnapshot snapshot(int labels) {
        return new RiskTrainingSnapshot(UUID.randomUUID(),"b".repeat(64),
                List.of(new RiskTrainingExample(UUID.randomUUID(),NOW.minusSeconds(7200),"风险",true),
                        new RiskTrainingExample(UUID.randomUUID(),NOW.minusSeconds(3600),"正常",false))
                        .subList(0,labels),labels==0?0:1,labels<2?0:1);
    }
}
