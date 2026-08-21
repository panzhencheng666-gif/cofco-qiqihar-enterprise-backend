package com.cofco.qiqihar.graintrade.analysis.domain;

import java.math.BigDecimal;

public record ObservableQuantityInput(
        BigDecimal openingObservableInventoryTonnes,
        BigDecimal expectedOutputTonnes,
        BigDecimal inflowTonnes,
        BigDecimal selfUseTonnes,
        BigDecimal outflowTonnes,
        BigDecimal endingObservableInventoryTonnes,
        boolean openingInventoryComplete,
        boolean endingInventoryComplete,
        int inventoryReviewGroupCount,
        int approvedRecordCount) {

    public ObservableQuantityInput {
        if (inventoryReviewGroupCount < 0 || approvedRecordCount < 0) {
            throw new IllegalArgumentException("Observable quantity counts cannot be negative");
        }
    }
}
