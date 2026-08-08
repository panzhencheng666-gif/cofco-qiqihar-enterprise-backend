package com.cofco.qiqihar.graintrade.importing.infrastructure;

import com.cofco.qiqihar.graintrade.importing.application.ImportJobRepository;
import com.cofco.qiqihar.graintrade.importing.domain.ImportJob;
import com.cofco.qiqihar.graintrade.importing.domain.ImportRowOutcome;
import com.cofco.qiqihar.graintrade.shared.application.ConflictException;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcImportJobRepository implements ImportJobRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final String reservationLockTimeout;

    public JdbcImportJobRepository(JdbcClient jdbc, ObjectMapper objectMapper,
            @Value("${qiqihar.import.reservation-lock-timeout:2s}") Duration reservationLockTimeout) {
        if (reservationLockTimeout.isZero() || reservationLockTimeout.isNegative()) {
            throw new IllegalArgumentException("Import reservation lock timeout must be positive");
        }
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.reservationLockTimeout = Math.max(1, reservationLockTimeout.toMillis()) + "ms";
    }

    @Override
    public ImportReservation reserve(String subjectId, String domainCode, String idempotencyKey,
            String digest, String workUnitCode, Instant now) {
        UUID id = UUID.randomUUID();
        String previousLockTimeout = setLocalLockTimeout(reservationLockTimeout);
        int inserted;
        try {
            inserted = jdbc.sql("""
                    INSERT INTO platform.import_job(import_job_id,domain_code,idempotency_key,content_sha256,source_content,
                      requested_by,work_unit_code,retry_of_import_job_id,status_code,created_at,completed_at)
                    VALUES(CAST(:id AS uuid),:domain,:key,:digest,'',:requestedBy,:workUnit,NULL,'COMPLETED',:now,:now)
                    ON CONFLICT(requested_by,domain_code,idempotency_key) DO NOTHING
                    """).param("id", id.toString()).param("domain", domainCode).param("key", idempotencyKey)
                    .param("digest", digest).param("requestedBy", subjectId).param("workUnit", workUnitCode)
                    .param("now", Timestamp.from(now)).update();
        } catch (DataAccessException exception) {
            if (hasSqlState(exception, "55P03")) {
                throw new ConflictException("IMPORT_RESERVATION_BUSY",
                        "Import reservation is still in progress; retry the request");
            }
            throw exception;
        }
        setLocalLockTimeout(previousLockTimeout);
        if (inserted == 1) {
            ImportJob reserved = new ImportJob(id, domainCode, idempotencyKey, digest, subjectId, workUnitCode,
                    null, "COMPLETED", now, now, List.of());
            return new ImportReservation(true, new StoredImportJob(reserved, ""));
        }
        StoredImportJob existing = findByIdempotency(subjectId, domainCode, idempotencyKey)
                .orElseThrow(() -> new IllegalStateException("Conflicting import reservation is not visible"));
        if (!existing.job().contentSha256().equals(digest)) {
            throw new ConflictException("IMPORT_IDEMPOTENCY_KEY_CONFLICT",
                    "Idempotency key was already used for different content");
        }
        return new ImportReservation(false, existing);
    }

    private String setLocalLockTimeout(String value) {
        String previous = jdbc.sql("SELECT current_setting('lock_timeout')").query(String.class).single();
        jdbc.sql("SELECT set_config('lock_timeout',:value,true)").param("value", value)
                .query(String.class).single();
        return previous;
    }

    private static boolean hasSqlState(Throwable exception, String expected) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException && expected.equals(sqlException.getSQLState())) return true;
        }
        return false;
    }

    @Override
    public Optional<StoredImportJob> findByIdempotency(String subjectId, String domainCode, String idempotencyKey) {
        return jdbc.sql("""
                SELECT import_job_id::text FROM platform.import_job
                WHERE requested_by=:subject AND domain_code=:domain AND idempotency_key=:key
                """).param("subject", subjectId).param("domain", domainCode).param("key", idempotencyKey)
                .query(String.class).optional().flatMap(id -> findById(UUID.fromString(id)));
    }

    @Override
    public Optional<StoredImportJob> findById(UUID jobId) {
        return jdbc.sql("""
                SELECT import_job_id::text,domain_code,idempotency_key,content_sha256,source_content,requested_by,
                  work_unit_code,retry_of_import_job_id::text,status_code,created_at,completed_at
                FROM platform.import_job WHERE import_job_id=CAST(:id AS uuid)
                """).param("id", jobId.toString()).query((row, index) -> new Header(
                        UUID.fromString(row.getString("import_job_id")), row.getString("domain_code"),
                        row.getString("idempotency_key"), row.getString("content_sha256"), row.getString("source_content"),
                        row.getString("requested_by"), row.getString("work_unit_code"), row.getString("retry_of_import_job_id"),
                        row.getString("status_code"), row.getTimestamp("created_at").toInstant(),
                        row.getTimestamp("completed_at").toInstant())).optional().map(header -> new StoredImportJob(
                                new ImportJob(header.id, header.domainCode, header.idempotencyKey, header.contentSha256,
                                        header.requestedBy, header.workUnitCode,
                                        header.retryOf == null ? null : UUID.fromString(header.retryOf), header.statusCode,
                                        header.createdAt, header.completedAt, rows(header.id)), header.sourceContent));
    }

    @Override
    public ImportJob complete(ImportJob job, String sourceContent) {
        int updated = jdbc.sql("""
                UPDATE platform.import_job
                SET source_content=:content,retry_of_import_job_id=CAST(:retryOf AS uuid),status_code=:status,
                  completed_at=:completedAt
                WHERE import_job_id=CAST(:id AS uuid)
                """).param("id", job.id().toString()).param("content", sourceContent)
                .param("retryOf", job.retryOf() == null ? null : job.retryOf().toString())
                .param("status", job.statusCode()).param("completedAt", Timestamp.from(job.completedAt())).update();
        if (updated != 1) throw new IllegalStateException("Import reservation no longer exists");
        job.rows().forEach(row -> jdbc.sql("""
                INSERT INTO platform.import_row_result(import_job_id,row_number,outcome_code,error_code,error_message,
                  production_record_id,row_data)
                VALUES(CAST(:job AS uuid),:rowNumber,:outcome,:errorCode,:errorMessage,:recordId,CAST(:data AS jsonb))
                """).param("job", job.id().toString()).param("rowNumber", row.rowNumber()).param("outcome", row.outcomeCode())
                .param("errorCode", row.errorCode()).param("errorMessage", row.errorMessage())
                .param("recordId", row.productionRecordId()).param("data", json(row.values())).update());
        return job;
    }

    private List<ImportRowOutcome> rows(UUID jobId) {
        return jdbc.sql("""
                SELECT row_number,outcome_code,error_code,error_message,production_record_id,row_data::text
                FROM platform.import_row_result WHERE import_job_id=CAST(:id AS uuid) ORDER BY row_number
                """).param("id", jobId.toString()).query((row, index) -> new ImportRowOutcome(row.getInt("row_number"),
                        row.getString("outcome_code"), row.getString("error_code"), row.getString("error_message"),
                        row.getString("production_record_id"), values(row.getString("row_data")))).list();
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> values(String json) {
        try { return Map.copyOf(objectMapper.readValue(json, Map.class)); }
        catch (Exception exception) { throw new IllegalStateException("Stored import row data is invalid", exception); }
    }
    private String json(Map<String, String> values) {
        try { return objectMapper.writeValueAsString(values); }
        catch (Exception exception) { throw new IllegalStateException("Import row data cannot be serialized", exception); }
    }
    private record Header(UUID id, String domainCode, String idempotencyKey, String contentSha256, String sourceContent,
            String requestedBy, String workUnitCode, String retryOf, String statusCode,
            java.time.Instant createdAt, java.time.Instant completedAt) {}
}
