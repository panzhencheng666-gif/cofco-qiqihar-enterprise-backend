package com.cofco.qiqihar.riskintelligence.experttraining;

import com.cofco.qiqihar.graintrade.shared.application.ConflictException;
import com.cofco.qiqihar.graintrade.shared.application.ResourceNotFoundException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcExpertTrainingRepository implements ExpertTrainingRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public JdbcExpertTrainingRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public DatasetSnapshot saveDataset(ExpertDatasetValidator.ValidatedDataset dataset,
            String subject, Instant now) {
        String datasetId = dataset.dataset().path("datasetId").asText();
        jdbc.sql("SELECT true FROM (SELECT pg_advisory_xact_lock(hashtextextended(:datasetId,0))) locked")
                .param("datasetId", datasetId).query(Boolean.class).single();
        Optional<DatasetSnapshot> existing = jdbc.sql("""
                SELECT snapshot_id,dataset_id,version,dataset_sha256,train_count,valid_count,
                       test_count,created_at
                FROM risk.expert_dataset_snapshot
                WHERE dataset_id=:datasetId AND dataset_sha256=:sha
                """).param("datasetId", datasetId).param("sha", dataset.datasetSha256())
                .query(this::snapshot).optional();
        if (existing.isPresent()) return existing.orElseThrow();
        Integer version = jdbc.sql("""
                SELECT COALESCE(max(version),0)+1 FROM risk.expert_dataset_snapshot
                WHERE dataset_id=:datasetId
                """).param("datasetId", datasetId).query(Integer.class).single();
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO risk.expert_dataset_snapshot(
                  snapshot_id,dataset_id,version,dataset_sha256,dataset_json,
                  train_count,valid_count,test_count,created_by_subject,created_at)
                VALUES(:id,:datasetId,:version,:sha,CAST(:dataset AS jsonb),
                  :train,:valid,:test,:subject,:now)
                """).param("id", id).param("datasetId", datasetId).param("version", version)
                .param("sha", dataset.datasetSha256()).param("dataset", write(dataset.dataset()))
                .param("train", dataset.counts().get("train"))
                .param("valid", dataset.counts().get("valid"))
                .param("test", dataset.counts().get("test"))
                .param("subject", subject).param("now", timestamp(now)).update();
        return new DatasetSnapshot(id, datasetId, version, dataset.datasetSha256(), dataset.counts(),
                "NOT_EVALUATED", "CALLER_DECLARED", now);
    }

    @Override
    public TaskView requestTask(UUID snapshotId, String subject, String idempotencyKey,
            JsonNode config, String requestSha256, String modelReference, Instant now) {
        jdbc.sql("SELECT true FROM (SELECT pg_advisory_xact_lock(hashtextextended(:lockKey,0))) locked")
                .param("lockKey", subject.length() + ":" + subject + idempotencyKey)
                .query(Boolean.class).single();
        Optional<RequestIdentity> existing = jdbc.sql("""
                SELECT task_id,request_sha256 FROM risk.expert_training_task
                WHERE requested_by_subject=:subject AND idempotency_key=:key
                FOR UPDATE
                """).param("subject", subject).param("key", idempotencyKey)
                .query((row, index) -> new RequestIdentity(
                        row.getObject("task_id", UUID.class), row.getString("request_sha256")))
                .optional();
        if (existing.isPresent()) {
            RequestIdentity identity = existing.orElseThrow();
            if (!identity.requestSha256().equals(requestSha256)) {
                throw new ConflictException("EXPERT_TASK_IDEMPOTENCY_CONFLICT",
                        "同一幂等键已用于不同的专家训练请求");
            }
            return findTask(identity.taskId()).orElseThrow();
        }
        boolean snapshotExists = jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM risk.expert_dataset_snapshot WHERE snapshot_id=:id)
                """).param("id", snapshotId).query(Boolean.class).single();
        if (!snapshotExists) throw new ResourceNotFoundException("EXPERT_DATASET_NOT_FOUND", "专家数据集快照不存在");
        String fixedModelReference = jdbc.sql("""
                SELECT base_model_reference FROM risk.ai_model WHERE model_code=:modelCode
                """).param("modelCode", modelReference).query(String.class).optional()
                .orElseThrow(() -> new ResourceNotFoundException(
                        "EXPERT_MODEL_IDENTITY_NOT_FOUND", "齐粮专家训练模型身份不存在"));
        UUID taskId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO risk.expert_training_task(
                  task_id,dataset_snapshot_id,model_reference,config_json,request_sha256,
                  requested_by_subject,idempotency_key,status,created_at,updated_at)
                VALUES(:taskId,:snapshotId,:modelReference,CAST(:config AS jsonb),:requestSha,
                  :subject,:key,'QUEUED',:now,:now)
                """).param("taskId", taskId).param("snapshotId", snapshotId)
                .param("modelReference", fixedModelReference).param("config", write(config))
                .param("requestSha", requestSha256).param("subject", subject)
                .param("key", idempotencyKey).param("now", timestamp(now)).update();
        audit(taskId, "BUSINESS", subject, "REQUESTED", json.createObjectNode(), now);
        return findTask(taskId).orElseThrow();
    }

    @Override
    public Overview overview() {
        List<DatasetSnapshot> datasets = jdbc.sql("""
                SELECT snapshot_id,dataset_id,version,dataset_sha256,train_count,valid_count,
                       test_count,created_at FROM risk.expert_dataset_snapshot
                ORDER BY created_at DESC,snapshot_id DESC LIMIT 100
                """).query(this::snapshot).list();
        List<TaskView> tasks = jdbc.sql(taskSelect() +
                " ORDER BY created_at DESC,task_id DESC LIMIT 100").query(this::task).list();
        List<AuditView> audits = jdbc.sql("""
                SELECT event_id,task_id,actor_type,actor_id,event_code,details_json::text,occurred_at
                FROM risk.expert_training_audit
                ORDER BY occurred_at DESC,event_id DESC LIMIT 200
                """).query((row, index) -> new AuditView(
                        row.getObject("event_id", UUID.class), row.getObject("task_id", UUID.class),
                        row.getString("actor_type"), row.getString("actor_id"), row.getString("event_code"),
                        read(row.getString("details_json")), instant(row, "occurred_at"))).list();
        return new Overview(datasets, tasks, audits);
    }

    @Override
    public TaskView cancel(UUID taskId, String subject, Instant now) {
        TaskState state = lockState(taskId).orElseThrow(() ->
                new ResourceNotFoundException("EXPERT_TASK_NOT_FOUND", "专家训练任务不存在"));
        String next = ExpertTrainingStateMachine.cancel(state.status());
        if (!next.equals(state.status())) {
            if ("CANCELLED".equals(next)) {
                jdbc.sql("""
                        UPDATE risk.expert_training_task SET status='CANCELLED',
                          cancellation_requested_by_subject=:subject,cancellation_requested_at=:now,
                          cancelled_at=:now,completed_at=:now,updated_at=:now
                        WHERE task_id=:taskId
                        """).param("subject", subject).param("now", timestamp(now))
                        .param("taskId", taskId).update();
            } else {
                jdbc.sql("""
                        UPDATE risk.expert_training_task SET status='CANCEL_REQUESTED',
                          cancellation_requested_by_subject=:subject,cancellation_requested_at=:now,
                          updated_at=:now WHERE task_id=:taskId
                        """).param("subject", subject).param("now", timestamp(now))
                        .param("taskId", taskId).update();
            }
            audit(taskId, "BUSINESS", subject, "CANCEL_REQUESTED", json.createObjectNode(), now);
        }
        return findTask(taskId).orElseThrow();
    }

    @Override
    public Optional<Claim> claim(String nodeId, Instant now, Duration leaseDuration) {
        recoverExpired(now);
        Optional<UUID> taskId = jdbc.sql("""
                SELECT task_id FROM risk.expert_training_task
                WHERE status='QUEUED' ORDER BY created_at,task_id
                FOR UPDATE SKIP LOCKED LIMIT 1
                """).query(UUID.class).optional();
        if (taskId.isEmpty()) return Optional.empty();
        UUID id = taskId.orElseThrow();
        Instant leaseUntil = now.plus(leaseDuration);
        jdbc.sql("""
                UPDATE risk.expert_training_task
                SET status='RUNNING',lease_owner=:nodeId,lease_until=:leaseUntil,
                    attempt_count=attempt_count+1,progress_phase='PREPARING',progress_at=:now,updated_at=:now
                WHERE task_id=:taskId
                """).param("nodeId", nodeId).param("leaseUntil", timestamp(leaseUntil))
                .param("now", timestamp(now)).param("taskId", id).update();
        audit(id, "TRAINING_NODE", nodeId, "CLAIMED", json.createObjectNode(), now);
        return jdbc.sql("""
                SELECT task.task_id,snapshot.snapshot_id,snapshot.dataset_id,snapshot.version,
                       snapshot.dataset_sha256,snapshot.dataset_json::text,task.config_json::text,
                       task.model_reference,task.lease_until,task.attempt_count
                FROM risk.expert_training_task task
                JOIN risk.expert_dataset_snapshot snapshot ON snapshot.snapshot_id=task.dataset_snapshot_id
                WHERE task.task_id=:taskId
                """).param("taskId", id).query((row, index) -> new Claim(
                        id, id.toString(), row.getObject("snapshot_id", UUID.class),
                        row.getString("dataset_id"), row.getInt("version"), row.getString("dataset_sha256"),
                        read(row.getString("dataset_json")), read(row.getString("config_json")),
                        row.getString("model_reference"), instant(row, "lease_until"),
                        row.getInt("attempt_count"))).optional();
    }

    @Override
    public Optional<Heartbeat> heartbeat(UUID taskId, String nodeId, Instant now, Duration leaseDuration) {
        return jdbc.sql("""
                UPDATE risk.expert_training_task SET lease_until=:leaseUntil,updated_at=:now
                WHERE task_id=:taskId AND lease_owner=:nodeId AND lease_until>:now
                  AND status IN ('RUNNING','CANCEL_REQUESTED')
                RETURNING status,lease_until
                """).param("leaseUntil", timestamp(now.plus(leaseDuration))).param("now", timestamp(now))
                .param("taskId", taskId).param("nodeId", nodeId)
                .query((row, index) -> new Heartbeat("CANCEL_REQUESTED".equals(row.getString("status")),
                        instant(row, "lease_until"))).optional();
    }

    @Override
    public boolean progress(UUID taskId, String nodeId, int percent, String phase, Instant now) {
        return jdbc.sql("""
                UPDATE risk.expert_training_task
                SET progress_percent=:percent,progress_phase=:phase,progress_at=:now,updated_at=:now
                WHERE task_id=:taskId AND lease_owner=:nodeId AND lease_until>:now
                  AND status IN ('RUNNING','CANCEL_REQUESTED') AND progress_percent<=:percent
                """).param("percent", percent).param("phase", phase).param("now", timestamp(now))
                .param("taskId", taskId).param("nodeId", nodeId).update() == 1;
    }

    @Override
    public boolean ownsLease(UUID taskId, String nodeId, Instant now) {
        return jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM risk.expert_training_task
                  WHERE task_id=:taskId AND lease_owner=:nodeId AND lease_until>:now
                    AND status='RUNNING')
                """).param("taskId", taskId).param("nodeId", nodeId).param("now", timestamp(now))
                .query(Boolean.class).single();
    }

    @Override
    public boolean recordUpload(UUID taskId, String nodeId, String reference, String sha256, Instant now) {
        int changed = jdbc.sql("""
                UPDATE risk.expert_training_task
                SET uploaded_artifact_reference=:reference,uploaded_artifact_sha256=:sha,updated_at=:now
                WHERE task_id=:taskId AND lease_owner=:nodeId AND lease_until>:now AND status='RUNNING'
                  AND (uploaded_artifact_reference IS NULL OR
                       (uploaded_artifact_reference=:reference AND uploaded_artifact_sha256=:sha))
                """).param("reference", reference).param("sha", sha256).param("now", timestamp(now))
                .param("taskId", taskId).param("nodeId", nodeId).update();
        if (changed == 1) audit(taskId, "TRAINING_NODE", nodeId, "ARTIFACT_UPLOADED", json.createObjectNode(), now);
        return changed == 1;
    }

    @Override
    public boolean complete(UUID taskId, String nodeId, String reference, String sha256,
            JsonNode metrics, Instant now) {
        Optional<TerminalState> state = lockTerminal(taskId);
        if (state.isEmpty()) return false;
        TerminalState current = state.orElseThrow();
        if ("SUCCEEDED".equals(current.status())) {
            return reference.equals(current.artifactReference()) && sha256.equals(current.artifactSha256())
                    && metrics.equals(read(current.metrics()));
        }
        if (!"RUNNING".equals(current.status()) || !nodeId.equals(current.leaseOwner())
                || current.leaseUntil() == null || !current.leaseUntil().isAfter(now)
                || !reference.equals(current.uploadedReference()) || !sha256.equals(current.uploadedSha())) return false;
        jdbc.sql("""
                UPDATE risk.expert_training_task SET status='SUCCEEDED',lease_owner=NULL,lease_until=NULL,
                  progress_percent=100,progress_phase='COMPLETING',progress_at=:now,
                  artifact_reference=:reference,artifact_sha256=:sha,metrics_json=CAST(:metrics AS jsonb),
                  completed_at=:now,updated_at=:now WHERE task_id=:taskId
                """).param("now", timestamp(now)).param("reference", reference).param("sha", sha256)
                .param("metrics", write(metrics)).param("taskId", taskId).update();
        audit(taskId, "TRAINING_NODE", nodeId, "SUCCEEDED", json.createObjectNode(), now);
        return true;
    }

    @Override
    public boolean fail(UUID taskId, String nodeId, String code, String message, Instant now) {
        Optional<TerminalState> state = lockTerminal(taskId);
        if (state.isEmpty()) return false;
        TerminalState current = state.orElseThrow();
        if ("FAILED".equals(current.status())) {
            return code.equals(current.failureCode()) && message.equals(current.failureMessage());
        }
        if (!"RUNNING".equals(current.status())
                || !nodeId.equals(current.leaseOwner()) || current.leaseUntil() == null
                || !current.leaseUntil().isAfter(now)) return false;
        jdbc.sql("""
                UPDATE risk.expert_training_task SET status='FAILED',lease_owner=NULL,lease_until=NULL,
                  failure_code=:code,failure_message=:message,completed_at=:now,updated_at=:now
                WHERE task_id=:taskId
                """).param("code", code).param("message", message).param("now", timestamp(now))
                .param("taskId", taskId).update();
        audit(taskId, "TRAINING_NODE", nodeId, "FAILED", json.createObjectNode(), now);
        return true;
    }

    @Override
    public boolean acknowledgeCancelled(UUID taskId, String nodeId, Instant now) {
        int changed = jdbc.sql("""
                UPDATE risk.expert_training_task SET status='CANCELLED',lease_owner=NULL,lease_until=NULL,
                  cancelled_at=:now,completed_at=:now,updated_at=:now
                WHERE task_id=:taskId AND status='CANCEL_REQUESTED' AND lease_owner=:nodeId
                  AND lease_until>:now
                """).param("now", timestamp(now)).param("taskId", taskId).param("nodeId", nodeId).update();
        if (changed == 1) audit(taskId, "TRAINING_NODE", nodeId, "CANCELLED", json.createObjectNode(), now);
        return changed == 1;
    }

    private void recoverExpired(Instant now) {
        List<TaskState> expired = jdbc.sql("""
                SELECT task_id,status,attempt_count FROM risk.expert_training_task
                WHERE status IN ('RUNNING','CANCEL_REQUESTED') AND lease_until<=:now
                ORDER BY task_id FOR UPDATE SKIP LOCKED
                """).param("now", timestamp(now)).query((row, index) -> new TaskState(
                        row.getObject("task_id", UUID.class), row.getString("status"),
                        row.getInt("attempt_count"))).list();
        for (TaskState task : expired) {
            var expiry = ExpertTrainingStateMachine.expiredLease(task.status(), task.attemptCount());
            boolean terminal = "FAILED".equals(expiry.status()) || "CANCELLED".equals(expiry.status());
            jdbc.sql("""
                    UPDATE risk.expert_training_task SET status=:status,lease_owner=NULL,lease_until=NULL,
                      progress_percent=CASE WHEN :resetAttemptState THEN 0 ELSE progress_percent END,
                      progress_phase=CASE WHEN :resetAttemptState THEN NULL ELSE progress_phase END,
                      progress_at=CASE WHEN :resetAttemptState THEN NULL ELSE progress_at END,
                      uploaded_artifact_reference=CASE WHEN :resetAttemptState THEN NULL ELSE uploaded_artifact_reference END,
                      uploaded_artifact_sha256=CASE WHEN :resetAttemptState THEN NULL ELSE uploaded_artifact_sha256 END,
                      failure_code=:failureCode,failure_message=:failureMessage,
                      cancelled_at=:cancelledAt,completed_at=:completedAt,updated_at=:now
                    WHERE task_id=:taskId
                    """).param("status", expiry.status())
                    .param("resetAttemptState", expiry.resetAttemptState())
                    .param("failureCode", expiry.failureCode())
                    .param("failureMessage", expiry.failureCode() == null ? null : "训练节点离线，重试次数已耗尽")
                    .param("cancelledAt", "CANCELLED".equals(expiry.status()) ? timestamp(now) : null)
                    .param("completedAt", terminal ? timestamp(now) : null).param("now", timestamp(now))
                    .param("taskId", task.taskId()).update();
            audit(task.taskId(), "SYSTEM", "lease-recovery",
                    terminal ? expiry.status() : "LEASE_RECOVERED", json.createObjectNode(), now);
        }
    }

    private Optional<TaskView> findTask(UUID taskId) {
        return jdbc.sql(taskSelect() + " WHERE task_id=:taskId")
                .param("taskId", taskId).query(this::task).optional();
    }

    private Optional<TaskState> lockState(UUID taskId) {
        return jdbc.sql("""
                SELECT task_id,status,attempt_count FROM risk.expert_training_task
                WHERE task_id=:taskId FOR UPDATE
                """).param("taskId", taskId).query((row, index) -> new TaskState(
                        row.getObject("task_id", UUID.class), row.getString("status"),
                        row.getInt("attempt_count"))).optional();
    }

    private Optional<TerminalState> lockTerminal(UUID taskId) {
        return jdbc.sql("""
                SELECT status,lease_owner,lease_until,uploaded_artifact_reference,
                       uploaded_artifact_sha256,artifact_reference,artifact_sha256,
                       metrics_json::text,failure_code,failure_message
                FROM risk.expert_training_task WHERE task_id=:taskId FOR UPDATE
                """).param("taskId", taskId).query((row, index) -> new TerminalState(
                        row.getString("status"), row.getString("lease_owner"), instant(row, "lease_until"),
                        row.getString("uploaded_artifact_reference"), row.getString("uploaded_artifact_sha256"),
                        row.getString("artifact_reference"), row.getString("artifact_sha256"),
                        row.getString("metrics_json"), row.getString("failure_code"),
                        row.getString("failure_message"))).optional();
    }

    private void audit(UUID taskId, String actorType, String actorId, String code,
            JsonNode details, Instant now) {
        jdbc.sql("""
                INSERT INTO risk.expert_training_audit(
                  event_id,task_id,actor_type,actor_id,event_code,details_json,occurred_at)
                VALUES(:id,:taskId,:actorType,:actorId,:code,CAST(:details AS jsonb),:now)
                """).param("id", UUID.randomUUID()).param("taskId", taskId)
                .param("actorType", actorType).param("actorId", actorId).param("code", code)
                .param("details", write(details)).param("now", timestamp(now)).update();
    }

    private DatasetSnapshot snapshot(ResultSet row, int index) throws SQLException {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("train", row.getInt("train_count"));
        counts.put("valid", row.getInt("valid_count"));
        counts.put("test", row.getInt("test_count"));
        return new DatasetSnapshot(row.getObject("snapshot_id", UUID.class), row.getString("dataset_id"),
                row.getInt("version"), row.getString("dataset_sha256"), Map.copyOf(counts),
                "NOT_EVALUATED", "CALLER_DECLARED", instant(row, "created_at"));
    }

    private TaskView task(ResultSet row, int index) throws SQLException {
        return new TaskView(row.getObject("task_id", UUID.class),
                row.getObject("dataset_snapshot_id", UUID.class), row.getString("status"),
                row.getString("model_reference"), row.getInt("attempt_count"),
                row.getInt("progress_percent"), row.getString("progress_phase"),
                row.getString("failure_code"), row.getString("failure_message"),
                row.getString("artifact_reference"), row.getString("artifact_sha256"),
                row.getString("cancellation_requested_by_subject"),
                instant(row, "cancellation_requested_at"), instant(row, "cancelled_at"),
                instant(row, "completed_at"),
                instant(row, "created_at"), instant(row, "updated_at"));
    }

    private static String taskSelect() {
        return """
                SELECT task_id,dataset_snapshot_id,status,model_reference,attempt_count,
                       progress_percent,progress_phase,failure_code,failure_message,
                       artifact_reference,artifact_sha256,cancellation_requested_by_subject,
                       cancellation_requested_at,cancelled_at,completed_at,created_at,updated_at
                FROM risk.expert_training_task
                """;
    }

    private String write(JsonNode value) { return json.writeValueAsString(value); }
    private JsonNode read(String value) { return json.readTree(value); }
    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static Instant instant(ResultSet row, String column) throws SQLException {
        Timestamp value = row.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private record RequestIdentity(UUID taskId, String requestSha256) { }
    private record TaskState(UUID taskId, String status, int attemptCount) { }
    private record TerminalState(String status, String leaseOwner, Instant leaseUntil,
            String uploadedReference, String uploadedSha, String artifactReference,
            String artifactSha256, String metrics, String failureCode, String failureMessage) { }
}
