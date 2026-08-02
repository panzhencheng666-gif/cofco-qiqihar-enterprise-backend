package com.cofco.qiqihar.graintrade.market.application;

import com.cofco.qiqihar.graintrade.market.domain.MarketMonitoringRecord;
import java.util.List;
import java.util.Map;

public record MarketRecordView(
        MarketMonitoringRecord record, Map<String, String> coreValues, List<String> allowedActions) {
    public MarketRecordView {
        coreValues = java.util.Collections.unmodifiableMap(
                new java.util.LinkedHashMap<>(coreValues));
        allowedActions = List.copyOf(allowedActions);
    }
}
