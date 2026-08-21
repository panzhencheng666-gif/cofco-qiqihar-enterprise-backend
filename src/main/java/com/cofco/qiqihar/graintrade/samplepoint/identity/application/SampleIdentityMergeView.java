package com.cofco.qiqihar.graintrade.samplepoint.identity.application;

import com.cofco.qiqihar.graintrade.samplepoint.identity.application.SampleIdentityGovernanceWorkbook.Row;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class SampleIdentityMergeView {
    private SampleIdentityMergeView() {}

    public record ExportFile(String filename, byte[] bytes, UUID batchId, int rowCount) {
        public ExportFile {
            bytes = bytes.clone();
        }
    }

    public record ExportSnapshot(
            UUID batchId, String exportedBy, String workUnitCode, Instant exportedAt, List<Row> rows) {
        public ExportSnapshot {
            rows = List.copyOf(rows);
        }
    }

    public record RowResult(
            int rowNumber, String sourceRecordId, String outcomeCode, String message) {}

    public record JobView(
            UUID jobId, UUID batchId, String statusCode, int acceptedRows,
            int pendingRequests, int skippedRows, int failedRows,
            String idempotencyKey, Instant createdAt, List<RowResult> rowResults) {
        public JobView {
            rowResults = List.copyOf(rowResults);
        }
    }

    public record JobSnapshot(
            JobView view, String contentSha256, String requestedBy, String workUnitCode) {}

    public record RequestSnapshot(
            UUID requestId, UUID jobId, UUID exportBatchId, String sourceDomain,
            String sourceRecordId, long expectedSourceVersion, UUID currentSamplePointId,
            UUID targetSamplePointId, String stableSubjectId, String regionCode,
            java.math.BigDecimal longitude, java.math.BigDecimal latitude,
            String duplicateIdentityGroup, String requestedBy, String workUnitCode,
            String reviewBasis, Instant submittedAt) {}

    public record ReviewView(
            UUID requestId, String sourceDomain, String sourceRecordId,
            UUID currentSamplePointId, UUID targetSamplePointId, String regionCode,
            String reviewBasis, String requestedBy, String statusCode,
            String reviewedBy, String reviewReason, Instant reviewedAt,
            UUID resolutionBatchId, boolean privilegedSelfReview) {}
}
