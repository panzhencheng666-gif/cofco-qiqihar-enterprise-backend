package com.cofco.qiqihar.graintrade.analysis.domain;

import java.math.BigDecimal;

public record ObservableQuantityInput(
        BigDecimal openingObservableInventoryTonnes,
        BigDecimal expectedOutputTonnes,
        BigDecimal inflowTonnes,
        BigDecimal selfUseTonnes,
        BigDecimal outflowTonnes,
        BigDecimal endingObservableInventoryTonnes,
        boolean inventoryMutuallyExclusive,
        int approvedRecordCount) {

    public ObservableQuantityInput {
        if (approvedRecordCount < 0) {
            throw new IllegalArgumentException("Approved record count cannot be negative");
        }
    }
}
