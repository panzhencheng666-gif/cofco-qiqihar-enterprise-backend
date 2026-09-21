package com.cofco.qiqihar.graintrade.risk.application;

import java.util.UUID;

public record RiskTrainingClaim(
        UUID executionId,
        UUID trainingPolicyId,
        UUID modelId,
        String modelCode,
        String modelName,
        String modelKind,
        String domainCode,
        String baseModelReference,
        int trainingWindowDays,
        int minimumNewLabels,
        long randomSeed) { }
