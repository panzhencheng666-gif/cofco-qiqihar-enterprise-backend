package com.cofco.qiqihar.riskintelligence.trainingnode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cofco.qiqihar.graintrade.risk.application.RiskModelLifecycleRepository;
import com.cofco.qiqihar.graintrade.risk.application.RiskModelScore;
import com.cofco.qiqihar.graintrade.risk.application.RiskScoringTask;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RemoteScoringCoordinatorTest {
    private static final Instant NOW=Instant.parse("2026-09-21T05:00:00Z");

    @Test
    void bindsAMacScoreToTheExactPendingModelArtifactAndAssessment() {
        RiskModelLifecycleRepository repository=mock(RiskModelLifecycleRepository.class);
        RiskScoringTask task=new RiskScoringTask(UUID.randomUUID(),3,"DOMAIN_LLM","base",
                "risk-artifact://sha256/"+"a".repeat(64),"a".repeat(64),UUID.randomUUID(),
                "evidence","SHADOW");
        when(repository.findPendingScoringTasks(NOW,200)).thenReturn(List.of(task));
        var coordinator=new RemoteScoringCoordinator(repository,
                Clock.fixed(NOW,ZoneOffset.UTC),Duration.ofMinutes(35));

        assertThat(coordinator.claimNext("mac-m5-max")).isPresent();
        var completion=new RemoteScoringCoordinator.RemoteScoreCompletion(task.modelId(),
                task.modelVersion(),task.assessmentId(),task.artifactReference(),
                task.artifactSha256(),true,0.81d);
        assertThat(coordinator.complete("mac-m5-max",completion)).isTrue();

        verify(repository).recordPrediction(task,new RiskModelScore(true,0.81d),NOW);
        assertThat(coordinator.complete("other-node",completion)).isFalse();
    }
}
