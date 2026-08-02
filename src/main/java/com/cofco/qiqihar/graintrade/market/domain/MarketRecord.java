package com.cofco.qiqihar.graintrade.market.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record MarketRecord(String id, Map<String, Object> values) {

    public MarketRecord {
        values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
}
