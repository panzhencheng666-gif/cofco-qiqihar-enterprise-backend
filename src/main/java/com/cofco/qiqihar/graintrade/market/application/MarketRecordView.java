package com.cofco.qiqihar.graintrade.market.application;

import com.cofco.qiqihar.graintrade.market.domain.MarketMonitoringRecord;
import java.util.List;

public record MarketRecordView(MarketMonitoringRecord record, List<String> allowedActions) {
    public MarketRecordView {
        allowedActions = List.copyOf(allowedActions);
    }
}
