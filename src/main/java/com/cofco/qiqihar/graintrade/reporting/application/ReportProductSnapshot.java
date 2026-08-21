package com.cofco.qiqihar.graintrade.reporting.application;

import java.time.Instant;
import java.util.List;

/** One product's approved-data contribution to a server-owned comprehensive report snapshot. */
public record ReportProductSnapshot(
        String productCode,
        String productLabel,
        List<DomainSnapshot> domains) {

    public ReportProductSnapshot {
        domains = List.copyOf(domains);
    }

    public record DomainSnapshot(
            String domainCode,
            String domainLabel,
            long approvedRecordCount,
            Instant dataCutoff,
            List<ReportBusinessMetric> metrics) {

        public DomainSnapshot {
            metrics = List.copyOf(metrics);
        }
    }
}
