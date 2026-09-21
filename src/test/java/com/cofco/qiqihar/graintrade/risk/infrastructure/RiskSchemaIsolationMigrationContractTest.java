package com.cofco.qiqihar.graintrade.risk.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class RiskSchemaIsolationMigrationContractTest {
    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V217__isolate_risk_schema_runtime.sql");

    @Test
    void changesOnlyRiskSchemaAndRemovesBusinessForeignKeys() throws IOException {
        String sql = Files.readString(MIGRATION);

        assertThat(sql)
                .contains("SET lock_timeout = '2s'")
                .contains("SET statement_timeout = '30s'")
                .contains("CREATE TABLE risk.source_fact_snapshot")
                .contains("source_record_id varchar(200) NOT NULL")
                .contains("source_version varchar(160) NOT NULL")
                .contains("payload_sha256 char(64) NOT NULL")
                .contains("REVOKE CREATE ON SCHEMA risk FROM PUBLIC")
                .contains("inventory_source_facility_code_fkey")
                .contains("inventory_source_event_product_code_fkey")
                .contains("inventory_balance_product_code_fkey")
                .doesNotContain("ALTER TABLE platform.")
                .doesNotContain("ALTER TABLE overview.")
                .doesNotContain("UPDATE platform.")
                .doesNotContain("UPDATE overview.")
                .doesNotContain("DELETE FROM platform.")
                .doesNotContain("DELETE FROM overview.");
    }

    @Test
    void makesSourceSnapshotsImmutableAndIdempotent() throws IOException {
        String sql = Files.readString(MIGRATION);

        assertThat(sql)
                .contains("UNIQUE (source_system,source_record_type,source_record_id,source_version)")
                .contains("CREATE FUNCTION risk.reject_source_fact_snapshot_mutation()")
                .contains("BEFORE UPDATE OR DELETE ON risk.source_fact_snapshot")
                .contains("Source fact snapshots are immutable")
                .contains("CHECK (payload_sha256 ~ '^[0-9a-f]{64}$')")
                .doesNotContain("password", "secret", "private_key");
    }
}
