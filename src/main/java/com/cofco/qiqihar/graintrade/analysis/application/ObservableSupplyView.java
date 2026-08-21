package com.cofco.qiqihar.graintrade.analysis.application;

import com.cofco.qiqihar.graintrade.analysis.domain.ObservableSupplyCalculation;

public record ObservableSupplyView(
        ObservableSupplyCalculation calculation,
        ObservableInventoryBreakdown inventory) {
    public ObservableSupplyView {
        if (calculation == null || inventory == null) {
            throw new IllegalArgumentException("Observable supply view is incomplete");
        }
    }
}
