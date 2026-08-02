package com.cofco.qiqihar.graintrade.market.application;

import java.util.List;

public record MarketFormDefinition(
        String productCode, String objectTypeCode,
        List<MarketCoreFieldDefinition> coreFields, List<MarketFactGroup> groups) {
    public MarketFormDefinition {
        coreFields = List.copyOf(coreFields);
        groups = List.copyOf(groups);
    }
}
