package com.cofco.qiqihar.graintrade.market.application;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record MarketListItem(
        String id, Map<String, String> values, List<String> allowedActions, long version) {
    public MarketListItem {
        values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        allowedActions = List.copyOf(allowedActions);
    }
}
