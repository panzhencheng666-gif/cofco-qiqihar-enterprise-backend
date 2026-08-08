package com.cofco.qiqihar.graintrade.market.application;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record MarketMonitoringDraft(
        String productCode, Map<String, String> coreValues, Map<String, BigDecimal> facts,
        List<UUID> evidencePhotoIds) {
    public MarketMonitoringDraft(
            String productCode, Map<String, String> coreValues, Map<String, BigDecimal> facts) {
        this(productCode, coreValues, facts, List.of());
    }

    public MarketMonitoringDraft {
        Map<String, String> normalized = new LinkedHashMap<>();
        if (coreValues != null) {
            coreValues.forEach(normalized::put);
        }
        coreValues = Collections.unmodifiableMap(normalized);
        facts = facts == null ? Map.of() : Map.copyOf(facts);
        evidencePhotoIds = evidencePhotoIds == null ? List.of() : List.copyOf(evidencePhotoIds);
    }
}
