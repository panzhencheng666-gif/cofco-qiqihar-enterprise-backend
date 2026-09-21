package com.cofco.qiqihar.graintrade.risk.application;

import java.util.List;
import java.util.UUID;

public record RiskTrainingSnapshot(
        UUID trainingSnapshotId,
        String dataSha256,
        List<RiskTrainingExample> examples,
        long positiveLabelCount,
        long negativeLabelCount) {
    public RiskTrainingSnapshot {
        examples=List.copyOf(examples);
    }
}
