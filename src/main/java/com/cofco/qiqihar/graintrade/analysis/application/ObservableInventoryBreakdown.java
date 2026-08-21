package com.cofco.qiqihar.graintrade.analysis.application;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ObservableInventoryBreakdown(
        BigDecimal productionOpeningTonnes,
        BigDecimal enterpriseOpeningTonnes,
        BigDecimal productionEndingTonnes,
        BigDecimal enterpriseEndingTonnes,
        boolean openingComplete,
        boolean endingComplete,
        int adoptedRecordCount,
        int reviewGroupCount,
        LocalDate enterpriseOpeningObservedFrom,
        LocalDate enterpriseOpeningObservedThrough,
        LocalDate enterpriseEndingObservedFrom,
        LocalDate enterpriseEndingObservedThrough) {

    public ObservableInventoryBreakdown {
        requireNonNegative(productionOpeningTonnes);
        requireNonNegative(enterpriseOpeningTonnes);
        requireNonNegative(productionEndingTonnes);
        requireNonNegative(enterpriseEndingTonnes);
        if (adoptedRecordCount < 0 || reviewGroupCount < 0) {
            throw new IllegalArgumentException("Inventory breakdown counts cannot be negative");
        }
        requireObservationRange(
                enterpriseOpeningTonnes,
                enterpriseOpeningObservedFrom,
                enterpriseOpeningObservedThrough);
        requireObservationRange(
                enterpriseEndingTonnes,
                enterpriseEndingObservedFrom,
                enterpriseEndingObservedThrough);
    }

    private static void requireNonNegative(BigDecimal value) {
        if (value != null && value.signum() < 0) {
            throw new IllegalArgumentException("Inventory breakdown values cannot be negative");
        }
    }

    private static void requireObservationRange(
            BigDecimal value, LocalDate observedFrom, LocalDate observedThrough) {
        if ((value == null) != (observedFrom == null)
                || (observedFrom == null) != (observedThrough == null)
                || (observedFrom != null && observedFrom.isAfter(observedThrough))) {
            throw new IllegalArgumentException(
                    "Enterprise inventory value and observation dates must stay aligned");
        }
    }
}
