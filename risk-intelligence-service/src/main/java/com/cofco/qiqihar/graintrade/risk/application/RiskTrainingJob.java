package com.cofco.qiqihar.graintrade.risk.application;

import java.util.List;
import java.util.UUID;

public record RiskTrainingJob(
        UUID modelId,
        String modelCode,
        String modelKind,
        String domainCode,
        String baseModelReference,
        int modelVersion,
        UUID trainingSnapshotId,
        long randomSeed,
        List<RiskTrainingExample> examples) {
    public RiskTrainingJob {
        examples=List.copyOf(examples);
    }

    public RiskTrainingJob(UUID modelId,String modelCode,String domainCode,int modelVersion,
            UUID trainingSnapshotId,long randomSeed,List<RiskTrainingExample> examples) {
        this(modelId,modelCode,"RISK_CLASSIFIER",domainCode,
                "builtin://bernoulli-naive-bayes/v1",modelVersion,trainingSnapshotId,randomSeed,examples);
    }
}
