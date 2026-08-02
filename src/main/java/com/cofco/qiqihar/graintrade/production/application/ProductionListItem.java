package com.cofco.qiqihar.graintrade.production.application;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ProductionListItem(
        String id, Map<String, String> values, List<String> allowedActions, long version) {
    public ProductionListItem {
        values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        allowedActions = List.copyOf(allowedActions);
    }
}
