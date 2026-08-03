package com.cofco.qiqihar.graintrade.importing.infrastructure;

import com.cofco.qiqihar.graintrade.importing.application.ImportJobRepository;
import com.cofco.qiqihar.graintrade.importing.domain.ImportJob;
import com.cofco.qiqihar.graintrade.importing.domain.ImportRowOutcome;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcImportJobRepository implements ImportJobRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcImportJobRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
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
    public ImportJob save(ImportJob job, String sourceContent) {
        jdbc.sql("""
                INSERT INTO platform.import_job(import_job_id,domain_code,idempotency_key,content_sha256,source_content,
                  requested_by,work_unit_code,retry_of_import_job_id,status_code,created_at,completed_at)
                VALUES(CAST(:id AS uuid),:domain,:key,:digest,:content,:requestedBy,:workUnit,CAST(:retryOf AS uuid),
                  :status,:createdAt,:completedAt)
                """).param("id", job.id().toString()).param("domain", job.domainCode()).param("key", job.idempotencyKey())
                .param("digest", job.contentSha256()).param("content", sourceContent).param("requestedBy", job.requestedBy())
                .param("workUnit", job.workUnitCode()).param("retryOf", job.retryOf() == null ? null : job.retryOf().toString())
                .param("status", job.statusCode()).param("createdAt", Timestamp.from(job.createdAt()))
                .param("completedAt", Timestamp.from(job.completedAt())).update();
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
