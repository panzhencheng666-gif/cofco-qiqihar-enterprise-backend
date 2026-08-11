package com.cofco.qiqihar.graintrade.overview.application;

import java.util.List;

public record OverviewDashboard(
        Scope scope,
        List<Metric> metrics,
        List<OverviewOption> regionPath,
        List<PriceTrendPoint> priceTrend,
        List<ProductShare> productStructure,
        List<RegionActivity> regionActivity,
        List<Alert> alerts,
        List<YoYComparison> cultivatedAreaYoY,
        List<YoYComparison> outputYoY) {

    public record Scope(
            long countyCount,
            long townshipCount,
            long villageCount,
            long reportingUnitCount,
            long approvedRecordCount,
            String latestUpdatedAt) {}

    public record Metric(
            String code,
            String name,
            String unitCode,
            String value,
            long sourceCount,
            String dataCutoff,
            String coverageStatus,
            String calculationVersion,
            List<RegionSurplusAuditSource> auditSources) {
        public Metric(String code, String name, String unitCode, String value, long sourceCount) {
            this(code, name, unitCode, value, sourceCount, null,
                    sourceCount > 0 ? "AVAILABLE" : "NO_APPROVED_SOURCES", null, List.of());
        }

        public Metric {
            auditSources = auditSources == null ? List.of() : List.copyOf(auditSources);
        }
    }

    public record PriceTrendPoint(String periodLabel, String value, long sourceCount) {}

    public record ProductShare(
            String productCode,
            String productName,
            String value,
            String unitCode,
            long sourceCount) {}

    public record RegionActivity(String regionCode, String regionName, long approvedCount, long totalCount) {}

    public record Alert(
            String code,
            String severity,
            String regionName,
            String message,
            String occurredOn) {}

    public record YoYComparison(
            String regionCode,
            String regionName,
            String currentValue,
            String previousValue,
            String unitCode,
            long currentSourceCount,
            long previousSourceCount) {}
}
