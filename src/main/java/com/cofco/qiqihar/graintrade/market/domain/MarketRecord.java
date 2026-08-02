package com.cofco.qiqihar.graintrade.market.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record MarketRecord(String id, Map<String, Object> values) {

    public MarketRecord {
        Objects.requireNonNull(values, "values must not be null");
        values.forEach((code, value) -> {
            if (value != null && !(value instanceof String) && !(value instanceof Number)) {
                throw new IllegalArgumentException(
                        "Market record value for " + code + " must be a string, number or null");
            }
        });
        values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
}
