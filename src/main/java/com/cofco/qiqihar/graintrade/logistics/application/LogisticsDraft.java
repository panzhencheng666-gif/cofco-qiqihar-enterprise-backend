package com.cofco.qiqihar.graintrade.logistics.application;

import java.util.Map;
import java.util.Collections;
import java.util.LinkedHashMap;

public record LogisticsDraft(String productCode, Map<String, String> values) {
    public LogisticsDraft {
        values = values == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
}
