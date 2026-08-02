package com.cofco.qiqihar.graintrade.market.application;

import java.util.List;

public record MarketFactGroup(
        String category, String label, int sortOrder, List<MarketFactDefinition> fields) {
    public MarketFactGroup {
        fields = List.copyOf(fields);
    }
}
