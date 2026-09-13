package com.cofco.qiqihar.graintrade.market.application;

import com.cofco.qiqihar.graintrade.market.domain.MarketStatus;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public record MarketListRow(
        String id, Map<String, String> values, MarketStatus status,
        Set<String> configuredActions, long version, String regionCode) {
    public MarketListRow(String id, Map<String, String> values, MarketStatus status,
            Set<String> configuredActions, long version) {
        this(id, values, status, configuredActions, version, null);
    }

    public MarketListRow {
        values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        configuredActions = Set.copyOf(configuredActions);
    }
}
