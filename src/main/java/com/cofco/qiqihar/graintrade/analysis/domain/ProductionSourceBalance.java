package com.cofco.qiqihar.graintrade.analysis.domain;

import java.math.BigDecimal;
import java.util.List;

public record ProductionSourceBalance(
        AnalysisQualityState qualityState,
        BigDecimal estimatedOutputTonnes,
        BigDecimal productionAvailableTonnes,
        BigDecimal knownDestinationTonnes,
        BigDecimal theoreticalEndingInventoryTonnes,
        BigDecimal reportedEndingInventoryTonnes,
        BigDecimal reconciliationDifferenceTonnes,
        List<String> issues) {

    public ProductionSourceBalance {
        if (qualityState == null || issues == null) {
            throw new IllegalArgumentException("Production source balance metadata is required");
        }
        issues = List.copyOf(issues);
    }
}
