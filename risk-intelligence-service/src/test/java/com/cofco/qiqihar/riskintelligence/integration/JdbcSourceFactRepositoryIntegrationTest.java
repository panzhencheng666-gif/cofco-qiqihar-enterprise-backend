package com.cofco.qiqihar.riskintelligence.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfEnvironmentVariable(named = "RISK_DB_URL", matches = ".+")
class JdbcSourceFactRepositoryIntegrationTest {
    @Autowired
    private SourceFactIngestionService ingestion;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void insertsRereadsDeduplicatesAndCannotMutateARealSnapshot() {
        String recordId = "integration-" + UUID.randomUUID();
        SourceFact fact = new SourceFact(
                "risk-integration-test",
                "BOUNDARY_PROBE",
                recordId,
                "v1",
                Instant.parse("2026-09-21T01:00:00Z"),
                Map.of("status", "VERIFIED", "sequence", 1));

        SourceFactReceipt first = ingestion.ingest(fact);
        SourceFactReceipt repeated = ingestion.ingest(fact);

        assertThat(first.created()).isTrue();
        assertThat(repeated.created()).isFalse();
        assertThat(repeated.snapshotId()).isEqualTo(first.snapshotId());
        assertThat(jdbc.sql("""
                SELECT count(*) FROM risk.source_fact_snapshot
                WHERE source_system='risk-integration-test' AND source_record_id=:recordId
                """).param("recordId", recordId).query(Integer.class).single()).isEqualTo(1);

        assertThatThrownBy(() -> jdbc.sql("""
                UPDATE risk.source_fact_snapshot SET source_status='WITHDRAWN'
                WHERE snapshot_id=:snapshotId
                """).param("snapshotId", first.snapshotId()).update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("Source fact snapshots are immutable");

        assertThatThrownBy(() -> jdbc.sql("""
                DELETE FROM risk.source_fact_snapshot WHERE snapshot_id=:snapshotId
                """).param("snapshotId", first.snapshotId()).update())
                .isInstanceOf(DataAccessException.class);
    }
}
