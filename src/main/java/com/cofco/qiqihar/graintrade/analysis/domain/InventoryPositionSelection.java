package com.cofco.qiqihar.graintrade.analysis.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;

public record InventoryPositionSelection(
        BigDecimal totalTonnes,
        int adoptedRecordCount,
        int reviewGroupCount,
        Set<String> adoptedRecordIds,
        LocalDate earliestObservedOn,
        LocalDate latestObservedOn) {

    public InventoryPositionSelection {
        if ((totalTonnes != null && totalTonnes.signum() < 0)
                || adoptedRecordCount < 0 || reviewGroupCount < 0
                || adoptedRecordIds == null || adoptedRecordCount != adoptedRecordIds.size()
                || (adoptedRecordCount == 0) != (earliestObservedOn == null)
                || (earliestObservedOn == null) != (latestObservedOn == null)
                || (earliestObservedOn != null && earliestObservedOn.isAfter(latestObservedOn))) {
            throw new IllegalArgumentException("Inventory position selection is invalid");
        }
        adoptedRecordIds = Set.copyOf(adoptedRecordIds);
    }
}
