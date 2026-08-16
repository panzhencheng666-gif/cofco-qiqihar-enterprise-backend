package com.cofco.qiqihar.graintrade.analysis.application;

import java.util.List;

public record MarketAnalysisView(List<ObservableMetric> metrics) {
    public MarketAnalysisView {
        if (metrics == null) throw new IllegalArgumentException("Market analysis metrics are required");
        metrics = List.copyOf(metrics);
    }
}
