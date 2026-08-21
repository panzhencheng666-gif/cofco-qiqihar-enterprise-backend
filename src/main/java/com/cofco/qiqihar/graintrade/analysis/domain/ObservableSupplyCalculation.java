package com.cofco.qiqihar.graintrade.analysis.domain;

import java.math.BigDecimal;
import java.util.List;

public record ObservableSupplyCalculation(
        AnalysisQualityState qualityState,
        BigDecimal openingObservableInventoryTonnes,
        BigDecimal expectedOutputTonnes,
        BigDecimal inflowTonnes,
        BigDecimal selfUseTonnes,
        BigDecimal outflowTonnes,
        BigDecimal endingObservableInventoryTonnes,
        BigDecimal inferredOtherAbsorptionTonnes,
        BigDecimal totalSupplyTonnes,
        BigDecimal totalUseTonnes,
        List<String> issues) {

    public ObservableSupplyCalculation {
        if (qualityState == null || issues == null) {
            throw new IllegalArgumentException("Observable supply result metadata is required");
        }
        issues = List.copyOf(issues);
    }
}
