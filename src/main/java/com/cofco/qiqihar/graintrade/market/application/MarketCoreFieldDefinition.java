package com.cofco.qiqihar.graintrade.market.application;

import java.util.List;

public record MarketCoreFieldDefinition(
        String code, String label, String controlType, String unit,
        Integer precision, Integer scale, int sortOrder, List<MarketFieldOption> options) {
    public MarketCoreFieldDefinition {
        options = List.copyOf(options);
    }
}
