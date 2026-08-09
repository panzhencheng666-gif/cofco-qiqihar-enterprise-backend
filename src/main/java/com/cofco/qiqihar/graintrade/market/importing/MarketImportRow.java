package com.cofco.qiqihar.graintrade.market.importing;

import java.math.BigDecimal;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Stable market-module command used by the shared import workflow. */
public record MarketImportRow(
        String productCode,
        Map<String, String> coreValues,
        Map<String, BigDecimal> facts,
        List<UUID> evidencePhotoIds) {
    public MarketImportRow {
        coreValues = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(coreValues));
        facts = Map.copyOf(facts);
        evidencePhotoIds = List.copyOf(evidencePhotoIds);
    }
}
