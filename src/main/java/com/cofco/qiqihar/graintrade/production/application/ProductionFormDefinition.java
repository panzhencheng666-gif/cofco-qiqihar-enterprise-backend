package com.cofco.qiqihar.graintrade.production.application;

import java.util.List;

public record ProductionFormDefinition(
        String productCode, String objectTypeCode, List<ProductionFactGroup> groups) {
    public ProductionFormDefinition {
        groups = List.copyOf(groups);
    }
}
