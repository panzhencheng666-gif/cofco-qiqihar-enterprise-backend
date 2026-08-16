package com.cofco.qiqihar.graintrade.analysis.application;

import com.cofco.qiqihar.graintrade.analysis.domain.ProductionSourceBalance;
import java.util.List;

public record ProductionAnalysisView(
        List<ObservableMetric> metrics,
        List<ProductionSourceBalance> sourceBalances) {

    public ProductionAnalysisView {
        if (metrics == null || sourceBalances == null) {
            throw new IllegalArgumentException("Production analysis collections are required");
        }
        metrics = List.copyOf(metrics);
        sourceBalances = List.copyOf(sourceBalances);
    }
}
