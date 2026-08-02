package com.cofco.qiqihar.graintrade.production.application;

import java.util.List;

public record ProductionFactGroup(
        String category, String label, int sortOrder, List<ProductionFactDefinition> fields) {
    public ProductionFactGroup {
        fields = List.copyOf(fields);
    }
}
