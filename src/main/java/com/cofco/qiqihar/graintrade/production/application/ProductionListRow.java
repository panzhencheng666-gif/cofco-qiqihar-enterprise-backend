package com.cofco.qiqihar.graintrade.production.application;

import com.cofco.qiqihar.graintrade.production.domain.ProductionStatus;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public record ProductionListRow(
        String id, Map<String, String> values, ProductionStatus status,
        Set<String> configuredActions, long version, String regionCode) {
    public ProductionListRow(String id, Map<String, String> values, ProductionStatus status,
            Set<String> configuredActions, long version) {
        this(id, values, status, configuredActions, version, null);
    }

    public ProductionListRow {
        values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        configuredActions = Set.copyOf(configuredActions);
    }
}
