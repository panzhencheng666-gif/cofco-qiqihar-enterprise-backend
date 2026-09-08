package com.cofco.qiqihar.graintrade.formalsamplepoint.infrastructure;

import com.cofco.qiqihar.graintrade.formalsamplepoint.application.FormalSampleRetirementBatch;
import com.cofco.qiqihar.graintrade.formalsamplepoint.application.FormalSampleRetirementBatchRepository;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcFormalSampleRetirementBatchRepository implements FormalSampleRetirementBatchRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public JdbcFormalSampleRetirementBatchRepository(DataSource source, ObjectMapper json) {
        this.jdbc = JdbcClient.create(source);
        this.json = json;
    }

    public void save(FormalSampleRetirementBatch batch) {
        jdbc.sql("""
                INSERT INTO registry.formal_sample_retirement_batch(batch_id,snapshot)
                VALUES(:id,CAST(:snapshot AS jsonb))
                """).param("id", batch.id()).param("snapshot", json.writeValueAsString(batch)).update();
    }

    public Optional<FormalSampleRetirementBatch> find(UUID id, boolean lock) {
        return jdbc.sql("""
                SELECT snapshot::text,reason,retired_count
                FROM registry.formal_sample_retirement_batch WHERE batch_id=:id
                """ + (lock ? " FOR UPDATE" : ""))
                .param("id", id).query((row, ignored) -> {
                    var batch = json.readValue(row.getString(1), FormalSampleRetirementBatch.class);
                    return new FormalSampleRetirementBatch(batch.id(), batch.actorSubjectId(),
                            batch.workUnitCode(), batch.authorizedRegions(), batch.businessDate(),
                            batch.expiresAt(), batch.candidates(), row.getString(2),
                            row.getObject(3, Integer.class));
                }).optional();
    }

    public void complete(UUID id, String reason, int count) {
        jdbc.sql("""
                UPDATE registry.formal_sample_retirement_batch
                SET reason=:reason,retired_count=:count,completed_at=CURRENT_TIMESTAMP
                WHERE batch_id=:id
                """).param("id", id).param("reason", reason).param("count", count).update();
    }
}
