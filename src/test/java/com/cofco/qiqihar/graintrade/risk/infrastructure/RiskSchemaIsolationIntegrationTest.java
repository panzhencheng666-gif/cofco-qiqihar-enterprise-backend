package com.cofco.qiqihar.graintrade.risk.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cofco.qiqihar.graintrade.testsupport.ProtectedTestDatabase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RiskSchemaIsolationIntegrationTest {
    private static final ProtectedTestDatabase DATABASE = ProtectedTestDatabase.shared();
    private static long modelCountBefore;
    private static long policyCountBefore;
    private Connection connection;

    @BeforeAll
    static void migrateInTwoSteps() throws SQLException {
        DATABASE.flywayToVersion("216").migrate();
        try (Connection connection = DATABASE.openConnection();
                Statement statement = connection.createStatement()) {
            modelCountBefore = count(statement, "risk.ai_model");
            policyCountBefore = count(statement, "risk.ai_training_policy");
        }
        DATABASE.flyway().migrate();
    }

    @BeforeEach
    void openTransaction() throws SQLException {
        connection = DATABASE.openConnection();
        connection.setAutoCommit(false);
    }

    @AfterEach
    void rollBackTransaction() throws SQLException {
        try {
            connection.rollback();
        } finally {
            connection.close();
        }
    }

    @Test
    void preservesExistingRiskRowsAndRemovesEveryCrossSchemaForeignKey() throws SQLException {
        assertThat(queryLong("SELECT count(*) FROM risk.ai_model")).isEqualTo(modelCountBefore);
        assertThat(queryLong("SELECT count(*) FROM risk.ai_training_policy"))
                .isEqualTo(policyCountBefore);
        assertThat(queryLong("""
                SELECT count(*)
                FROM pg_constraint constraint_value
                JOIN pg_class source_table ON source_table.oid=constraint_value.conrelid
                JOIN pg_namespace source_schema ON source_schema.oid=source_table.relnamespace
                JOIN pg_class target_table ON target_table.oid=constraint_value.confrelid
                JOIN pg_namespace target_schema ON target_schema.oid=target_table.relnamespace
                WHERE constraint_value.contype='f'
                  AND source_schema.nspname='risk'
                  AND target_schema.nspname<>'risk'
                """)).isZero();
    }

    @Test
    void rejectsDuplicateSourceVersions() throws SQLException {
        execute("SET LOCAL ROLE qiqihar_enterprise_runtime");
        execute(sourceSnapshotInsert("21700000-0000-0000-0000-000000000001", "v1", "a"));

        assertThatThrownBy(() -> execute(
                sourceSnapshotInsert("21700000-0000-0000-0000-000000000002", "v1", "a")))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("duplicate key value violates unique constraint");
    }

    @Test
    void rejectsSourceSnapshotUpdates() throws SQLException {
        execute("SET LOCAL ROLE qiqihar_enterprise_runtime");
        execute(sourceSnapshotInsert("21700000-0000-0000-0000-000000000001", "v1", "a"));
        assertThatThrownBy(() -> execute("""
                UPDATE risk.source_fact_snapshot SET source_status='WITHDRAWN'
                WHERE snapshot_id='21700000-0000-0000-0000-000000000001'
                """))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("permission denied for table source_fact_snapshot");
        connection.rollback();
        execute(sourceSnapshotInsert("21700000-0000-0000-0000-000000000001", "v1", "a"));
        assertThatThrownBy(() -> execute("""
                UPDATE risk.source_fact_snapshot SET source_status='WITHDRAWN'
                WHERE snapshot_id='21700000-0000-0000-0000-000000000001'
                """))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("Source fact snapshots are immutable");
    }

    @Test
    void rejectsSourceSnapshotDeletes() throws SQLException {
        execute("SET LOCAL ROLE qiqihar_enterprise_runtime");
        execute(sourceSnapshotInsert("21700000-0000-0000-0000-000000000001", "v1", "a"));
        assertThatThrownBy(() -> execute("""
                DELETE FROM risk.source_fact_snapshot
                WHERE snapshot_id='21700000-0000-0000-0000-000000000001'
                """))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("permission denied for table source_fact_snapshot");
        connection.rollback();
        execute(sourceSnapshotInsert("21700000-0000-0000-0000-000000000001", "v1", "a"));
        assertThatThrownBy(() -> execute("""
                DELETE FROM risk.source_fact_snapshot
                WHERE snapshot_id='21700000-0000-0000-0000-000000000001'
                """))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("Source fact snapshots are immutable");
    }

    private static String sourceSnapshotInsert(String id, String version, String hashCharacter) {
        return """
                INSERT INTO risk.source_fact_snapshot(
                  snapshot_id,source_system,source_record_type,source_record_id,source_version,
                  business_occurred_at,ingested_at,payload_sha256,payload)
                VALUES('%s','enterprise','BUSINESS_AUDIT','event-1','%s',
                  TIMESTAMPTZ '2026-09-21 01:00:00+00',TIMESTAMPTZ '2026-09-21 01:00:01+00',
                  repeat('%s',64),'{"actionCode":"SUBMITTED"}'::jsonb)
                """.formatted(id, version, hashCharacter);
    }

    private static long count(Statement statement, String relation) throws SQLException {
        try (ResultSet result = statement.executeQuery("SELECT count(*) FROM " + relation)) {
            result.next();
            return result.getLong(1);
        }
    }

    private long queryLong(String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private void execute(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
