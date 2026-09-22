package com.cofco.qiqihar.riskintelligence.experttraining;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public interface ExpertTrainingRepository {
    DatasetSnapshot saveDataset(ExpertDatasetValidator.ValidatedDataset dataset,
            String subject, Instant now);
    TaskView requestTask(UUID snapshotId, String subject, String idempotencyKey,
            JsonNode config, String requestSha256, String modelReference, Instant now);
    Overview overview();
    TaskView cancel(UUID taskId, String subject, Instant now);
    Optional<Claim> claim(String nodeId, Instant now, Duration leaseDuration);
    Optional<Heartbeat> heartbeat(UUID taskId, String nodeId, Instant now, Duration leaseDuration);
    boolean progress(UUID taskId, String nodeId, int percent, String phase, Instant now);
    boolean ownsLease(UUID taskId, String nodeId, Instant now);
    boolean recordUpload(UUID taskId, String nodeId, String reference, String sha256, Instant now);
    boolean complete(UUID taskId, String nodeId, String reference, String sha256,
            JsonNode metrics, Instant now);
    boolean fail(UUID taskId, String nodeId, String code, String message, Instant now);
    boolean acknowledgeCancelled(UUID taskId, String nodeId, Instant now);

    record DatasetSnapshot(UUID snapshotId, String datasetId, int version, String datasetSha256,
            Map<String, Integer> counts, String qualityStatus, String provenanceStatus,
            Instant createdAt) { }
    record TaskView(UUID taskId, UUID datasetSnapshotId, String status, String modelReference,
            int attemptCount, int progressPercent, String progressPhase, String failureCode,
            String failureMessage, String artifactReference, String artifactSha256,
            String cancellationRequestedBySubject, Instant cancellationRequestedAt,
            Instant cancelledAt, Instant completedAt,
            Instant createdAt, Instant updatedAt) { }
    record AuditView(UUID eventId, UUID taskId, String actorType, String actorId,
            String eventCode, JsonNode details, Instant occurredAt) { }
    record Overview(List<DatasetSnapshot> datasets, List<TaskView> tasks,
            List<AuditView> auditEvents) { }
    record Claim(UUID taskId, String runId, UUID snapshotId, String datasetId, int datasetVersion,
            String datasetSha256, JsonNode dataset, JsonNode config, String modelReference,
            Instant leaseUntil, int attempt) { }
    record Heartbeat(boolean cancelRequested, Instant leaseUntil) { }
}
