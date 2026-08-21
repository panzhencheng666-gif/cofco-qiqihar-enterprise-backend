package com.cofco.qiqihar.graintrade.market.importing;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/** Current authorized snapshot used to prefill a correction workbook row. */
public record MarketReturnedCorrectionRecord(
        String id, String productCode, String objectTypeCode, String regionCode,
        LocalDate tradeDate, int surveyYear, Integer surveyMonth, long version,
        Map<String, String> coreValues, Map<String, String> facts) {
    public MarketReturnedCorrectionRecord {
        coreValues = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(coreValues));
        facts = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(facts));
    }
}
