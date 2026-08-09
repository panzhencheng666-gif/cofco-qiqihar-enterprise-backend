package com.cofco.qiqihar.graintrade.logistics.importing;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record LogisticsImportRow(String productCode, Map<String, String> values) {
    public LogisticsImportRow {
        values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
}
