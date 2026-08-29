package com.cofco.qiqihar.graintrade.overview.application;

import java.util.List;

public record OverviewDashboardSummary(
        Scope scope,
        List<OverviewDashboard.Metric> metrics) {

    public record Scope(
            long prefectureCount,
            long countyCount,
            long townshipCount,
            long villageCount) {}
}
