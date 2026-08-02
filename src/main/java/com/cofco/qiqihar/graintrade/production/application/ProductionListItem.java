package com.cofco.qiqihar.graintrade.production.application;

import java.util.List;
import java.util.Map;

public record ProductionListItem(
        String id, Map<String, String> values, List<String> allowedActions, long version) {
    public ProductionListItem {
        values = Map.copyOf(values);
        allowedActions = List.copyOf(allowedActions);
    }
}
