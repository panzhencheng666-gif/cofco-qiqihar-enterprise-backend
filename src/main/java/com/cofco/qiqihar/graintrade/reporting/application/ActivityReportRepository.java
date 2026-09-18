package com.cofco.qiqihar.graintrade.reporting.application;

import java.time.Instant;
import java.util.List;

public interface ActivityReportRepository {
    Snapshot personal(String subjectId, Instant start, Instant cutoff);
    Snapshot system(Instant start, Instant cutoff);
    void saveExport(ExportRecord export);
    ActivityReportExport.Content exportContent(String exportId, String requestedBy);

    record EventCount(String actionCode, String aggregateType, String workUnitCode,
            String workUnitName, long count) {}
    record Snapshot(String displayName, String workUnitName, long effectiveUserCount,
            List<EventCount> events) {}
    record ExportRecord(String id, int periodDays, Instant periodStart, Instant periodEnd,
            Instant eventCutoff, String generatedBy, Instant generatedAt, String filename,
            String contentType, String sha256, byte[] bytes) {}
}
