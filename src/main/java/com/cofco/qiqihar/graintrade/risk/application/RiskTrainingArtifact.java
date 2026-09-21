package com.cofco.qiqihar.graintrade.risk.application;

import java.util.Map;

public record RiskTrainingArtifact(
        String artifactReference,
        String artifactSha256,
        Map<String,Object> metrics,
        Map<String,Object> thresholds,
        String algorithmCode,
        String algorithmVersion,
        String trainingKind) {
    public RiskTrainingArtifact {
        metrics=Map.copyOf(metrics);
        thresholds=Map.copyOf(thresholds);
    }
}
