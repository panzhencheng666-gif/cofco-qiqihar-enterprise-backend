package com.cofco.qiqihar.graintrade.market.application;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

public record MarketMonitoringDraft(
        String productCode, Map<String, String> coreValues, Map<String, BigDecimal> facts) {
    public MarketMonitoringDraft {
        Map<String, String> normalized = new LinkedHashMap<>();
        if (coreValues != null) {
            coreValues.forEach((code, value) -> {
                if (value != null) normalized.put(code, value);
            });
        }
        coreValues = Map.copyOf(normalized);
        facts = facts == null ? Map.of() : Map.copyOf(facts);
    }
}
