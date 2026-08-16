package com.cofco.qiqihar.graintrade.analysis.application;

import com.cofco.qiqihar.graintrade.analysis.domain.ObservableSupplyCalculation;

public record ObservableSupplyView(ObservableSupplyCalculation calculation) {
    public ObservableSupplyView {
        if (calculation == null) throw new IllegalArgumentException("Observable supply calculation is required");
    }
}
