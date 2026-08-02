package com.cofco.qiqihar.graintrade.production.application;

import com.cofco.qiqihar.graintrade.production.domain.ProductionRecord;
import java.util.List;

public record ProductionRecordView(ProductionRecord record, List<String> allowedActions) {
    public ProductionRecordView {
        allowedActions = List.copyOf(allowedActions);
    }
}
