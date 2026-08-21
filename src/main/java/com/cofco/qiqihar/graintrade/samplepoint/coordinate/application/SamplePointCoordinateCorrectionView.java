package com.cofco.qiqihar.graintrade.samplepoint.coordinate.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class SamplePointCoordinateCorrectionView {
    private SamplePointCoordinateCorrectionView() {}

    public record ExportFile(String filename, byte[] bytes, UUID batchId, int rowCount) {
        public ExportFile { bytes = bytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
    }

    public record Candidate(
            UUID samplePointId, long version, String canonicalName, String regionCode,
            String regionName, String kindCode, BigDecimal longitude, BigDecimal latitude) {}

    public record ExportSnapshot(
            UUID batchId, String requestedBy, String workUnitCode, Instant createdAt,
            List<SamplePointCoordinateCorrectionWorkbook.Row> rows) {
        public ExportSnapshot { rows = List.copyOf(rows); }
    }

    public record RowResult(
            int rowNumber, UUID samplePointId, String outcomeCode, String errorCode,
            String message, UUID requestId) {}

    public record JobView(
            UUID jobId, UUID batchId, String requestedBy, String workUnitCode,
            String statusCode, int totalRows, int pendingReviewRows, int failedRows,
            UUID retryOf, Instant createdAt, Instant completedAt, List<RowResult> rowResults) {
        public JobView { rowResults = List.copyOf(rowResults); }
    }

    public record JobSnapshot(
            JobView view, String idempotencyKey, String contentSha256,
            List<SamplePointCoordinateCorrectionWorkbook.Row> submittedRows) {
        public JobSnapshot { submittedRows = List.copyOf(submittedRows); }
    }

    public record RequestSnapshot(
            UUID requestId, UUID jobId, UUID batchId, UUID samplePointId,
            long expectedVersion, String canonicalName, String regionCode,
            BigDecimal originalLongitude, BigDecimal originalLatitude,
            BigDecimal correctedLongitude, BigDecimal correctedLatitude,
            String coordinateSource, String correctionNote, String requestedBy,
            String workUnitCode, Instant createdAt) {}

    public record ReviewView(
            UUID requestId, UUID samplePointId, String canonicalName, String regionCode,
            BigDecimal originalLongitude, BigDecimal originalLatitude,
            BigDecimal correctedLongitude, BigDecimal correctedLatitude,
            String coordinateSource, String correctionNote, String requestedBy,
            Instant createdAt, String statusCode, String reviewedBy,
            String reviewReason, Instant reviewedAt) {}
}
