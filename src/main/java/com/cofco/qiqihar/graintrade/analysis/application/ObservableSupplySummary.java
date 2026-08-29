package com.cofco.qiqihar.graintrade.analysis.application;

import java.time.OffsetDateTime;
import java.util.List;

public record ObservableSupplySummary(
        ObservableSupplyView supply,
        long sourceCount,
        OffsetDateTime dataCutoffAt,
        List<ObservableHeadlineMetric> headlineMetrics,
        List<ObservableEndingInventorySource> endingInventorySources) {

    public ObservableSupplySummary {
        if (supply == null || sourceCount < 0
                || (sourceCount == 0) != (dataCutoffAt == null)
                || headlineMetrics == null || endingInventorySources == null) {
            throw new IllegalArgumentException("Observable supply summary is incomplete");
        }
        headlineMetrics = List.copyOf(headlineMetrics);
        endingInventorySources = List.copyOf(endingInventorySources);
    }
}
