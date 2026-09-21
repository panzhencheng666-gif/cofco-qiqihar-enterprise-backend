package com.cofco.qiqihar.graintrade.risk.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record RiskTrainingExecutionSummary(
        UUID executionId,UUID modelId,String modelName,LocalDate scheduledLocalDate,
        String triggerCode,String statusCode,String outcomeCode,String outcomeMessage,
        UUID trainingSnapshotId,UUID trainingRunId,Instant createdAt,Instant startedAt,
        Instant completedAt) { }
