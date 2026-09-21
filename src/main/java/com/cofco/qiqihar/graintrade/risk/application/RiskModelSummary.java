package com.cofco.qiqihar.graintrade.risk.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

public record RiskModelSummary(
        UUID modelId,String modelName,String modelKind,String domainCode,
        String statusCode,boolean policyEnabled,
        LocalTime scheduledLocalTime,String scheduleTimezone,int trainingWindowDays,
        int minimumNewLabels,boolean automaticCandidateEnabled,boolean autoActivationEnabled,
        LocalDate lastScheduledDate,String lastExecutionStatus,String lastOutcomeCode,
        String lastOutcomeMessage,Instant lastCompletedAt,Integer latestVersion,
        String latestVersionStatus) { }
