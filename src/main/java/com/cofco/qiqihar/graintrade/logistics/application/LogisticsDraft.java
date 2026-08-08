package com.cofco.qiqihar.graintrade.logistics.application;

import com.cofco.qiqihar.graintrade.shared.application.BoundedInput;
import java.util.Map;
import java.util.Collections;
import java.util.LinkedHashMap;

public record LogisticsDraft(String productCode, Map<String, String> values) {
    public LogisticsDraft {
        values = values == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(values));
        BoundedInput.requireAggregateSize("INVALID_LOGISTICS_RECORD", values);
        BoundedInput.requireText("INVALID_LOGISTICS_RECORD", productCode);
        BoundedInput.requireMapText("INVALID_LOGISTICS_RECORD", values);
    }
}
