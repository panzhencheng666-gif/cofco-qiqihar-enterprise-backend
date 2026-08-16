package com.cofco.qiqihar.graintrade.analysis.application;

import java.util.List;

public record LogisticsAnalysisView(List<ObservableMetric> metrics) {
    public LogisticsAnalysisView {
        if (metrics == null) throw new IllegalArgumentException("Logistics analysis metrics are required");
        metrics = List.copyOf(metrics);
    }
}
