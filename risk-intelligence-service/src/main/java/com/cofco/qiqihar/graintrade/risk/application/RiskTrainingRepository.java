package com.cofco.qiqihar.graintrade.risk.application;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

public interface RiskTrainingRepository {
    List<RiskModelSummary> findModels();
    List<RiskTrainingExecutionSummary> findRecentExecutions(int limit);
    List<RiskModelActivationSummary> findRecentActivationEvents(int limit);
    boolean configureExternalLlm(String baseModelReference,Instant configuredAt);
    int enqueueDueDailyExecutions(Instant now);
    int failExpiredExecutions(Instant now);
    UUID enqueueManualExecution(UUID modelId,String requestedBySubject,Instant now);
    Optional<RiskTrainingClaim> claimNext(Instant now,String workerId,Duration leaseDuration);
    RiskTrainingSnapshot freezeTrainingSnapshot(RiskTrainingClaim claim,Instant cutoffAt);
    UUID createTrainingRun(RiskTrainingClaim claim,RiskTrainingSnapshot snapshot,
            Instant now,String algorithmCode);
    void markRunRunning(UUID trainingRunId,Instant startedAt);
    int nextModelVersion(UUID modelId);
    void completeRunAndCreateCandidate(UUID executionId,UUID trainingRunId,UUID modelId,
            String domainCode,int modelVersion,RiskTrainingArtifact artifact,Instant completedAt);
    void failRunAndExecution(UUID executionId,UUID trainingRunId,String failureCode,
            String failureMessage,Instant completedAt);
    void skipExecution(UUID executionId,String outcomeCode,String outcomeMessage,Instant completedAt);
}
