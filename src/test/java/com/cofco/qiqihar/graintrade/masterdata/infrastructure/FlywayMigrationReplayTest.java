package com.cofco.qiqihar.graintrade.masterdata.infrastructure;

import com.cofco.qiqihar.graintrade.testsupport.ProtectedTestDatabase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FlywayMigrationReplayTest {

    private static final ProtectedTestDatabase DATABASE = ProtectedTestDatabase.shared();
    private static final String[] BUSINESS_SCHEMAS = {
        "platform", "production", "market", "logistics", "supply", "reporting", "workflow", "overview"
    };

    @BeforeAll
    static void resetDedicatedTestDatabase() throws SQLException {
        try (Connection connection = DATABASE.openConnection();
                Statement statement = connection.createStatement()) {
            for (String schema : BUSINESS_SCHEMAS) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
            statement.execute("DROP TABLE IF EXISTS public.flyway_schema_history");
        }
    }

    @Test
    @Order(1)
    void appliesVersionedMigrationsOnceAndKeepsChecksumsAndDataStableOnSecondStartup() throws SQLException {
        Flyway firstStartup = flyway();
        MigrateResult firstResult = firstStartup.migrate();

        assertThat(firstResult.migrationsExecuted).isEqualTo(4);
        assertThat(existingBusinessSchemas()).containsExactlyInAnyOrder(BUSINESS_SCHEMAS);
        Map<String, Integer> firstChecksums = migrationChecksums();
        Map<String, Long> firstCounts = masterDataCounts();

        MigrateResult secondResult = flyway().migrate();

        assertThat(secondResult.migrationsExecuted).isZero();
        assertThat(migrationChecksums()).isEqualTo(firstChecksums);
        assertThat(firstChecksums).containsEntry("1", 578287895)
                .containsEntry("2", -1029775028)
                .containsEntry("3", -1102740881);
        assertThat(masterDataCounts()).isEqualTo(firstCounts);
        assertThat(firstCounts).containsEntry("region", 29L)
                .containsEntry("product", 3L)
                .containsEntry("cultivar", 2L)
                .containsEntry("object_type", 10L)
                .containsEntry("page_definition_field", 9L);
    }

    @Test
    @Order(2)
    void rejectsInvalidPageDefaultsAndRegionHierarchyAtTheDatabaseBoundary() throws SQLException {
        insertInvariantFixtures();
        try {
            assertInsertRejected("""
                    INSERT INTO platform.page_default_context
                        (business_domain, page_kind, default_business_batch_code)
                    VALUES ('MARKET', 'QUALITY', 'BATCH_A')
                    """);
            assertInsertRejected("""
                    INSERT INTO platform.page_default_context
                        (business_domain, page_kind, default_business_period_code, default_business_batch_code)
                    VALUES ('MARKET', 'QUALITY', 'PERIOD_B', 'BATCH_A')
                    """);
            assertInsertRejected("""
                    INSERT INTO platform.page_default_context
                        (business_domain, page_kind, default_product_code)
                    VALUES ('MARKET', 'QUALITY', 'CORN')
                    """);
            assertInsertRejected("""
                    INSERT INTO platform.region
                        (code, name, parent_code, administrative_level, sort_order)
                    VALUES ('990001', '非法地市', '230200', 'PREFECTURE', 990001)
                    """);
            assertInsertRejected("""
                    INSERT INTO platform.region
                        (code, name, parent_code, administrative_level, sort_order)
                    VALUES ('990002', '无父区县', NULL, 'COUNTY', 990002)
                    """);
            assertInsertRejected("""
                    INSERT INTO platform.region
                        (code, name, parent_code, administrative_level, sort_order)
                    VALUES ('990003', '自引用区县', '990003', 'COUNTY', 990003)
                    """);
            assertInsertRejected("""
                    INSERT INTO platform.region
                        (code, name, parent_code, administrative_level, sort_order)
                    VALUES ('990004', '区县下区县', '230202', 'COUNTY', 990004)
                    """);
        } finally {
            deleteInvariantFixtures();
        }
    }

    private Flyway flyway() {
        return DATABASE.flyway();
    }

    private Map<String, Integer> migrationChecksums() throws SQLException {
        Map<String, Integer> checksums = new LinkedHashMap<>();
        try (Connection connection = DATABASE.openConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT version, checksum FROM public.flyway_schema_history "
                                + "WHERE success ORDER BY installed_rank")) {
            while (rows.next()) {
                checksums.put(rows.getString(1), rows.getInt(2));
            }
        }
        return checksums;
    }

    private Map<String, Long> masterDataCounts() throws SQLException {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : new String[] {
                "region", "product", "cultivar", "object_type", "page_definition_field"
        }) {
            try (Connection connection = DATABASE.openConnection();
                    Statement statement = connection.createStatement();
                    ResultSet row = statement.executeQuery("SELECT count(*) FROM platform." + table)) {
                row.next();
                counts.put(table, row.getLong(1));
            }
        }
        return counts;
    }

    private java.util.List<String> existingBusinessSchemas() throws SQLException {
        java.util.List<String> schemas = new java.util.ArrayList<>();
        try (Connection connection = DATABASE.openConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT schema_name FROM information_schema.schemata "
                                + "WHERE schema_name IN ('platform','production','market','logistics',"
                                + "'supply','reporting','workflow','overview')")) {
            while (rows.next()) {
                schemas.add(rows.getString(1));
            }
        }
        return schemas;
    }

    private void insertInvariantFixtures() throws SQLException {
        try (Connection connection = DATABASE.openConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO platform.business_period
                        (code, name, starts_on, ends_on, sort_order)
                    VALUES
                        ('PERIOD_A', '约束测试期间A', DATE '2026-01-01', DATE '2026-06-30', 9001),
                        ('PERIOD_B', '约束测试期间B', DATE '2026-07-01', DATE '2026-12-31', 9002)
                    """);
            statement.execute("""
                    INSERT INTO platform.business_batch
                        (code, name, business_period_code, sort_order)
                    VALUES ('BATCH_A', '约束测试批次A', 'PERIOD_A', 9001)
                    """);
        }
    }

    private void deleteInvariantFixtures() throws SQLException {
        try (Connection connection = DATABASE.openConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM platform.page_default_context WHERE business_domain = 'MARKET' AND page_kind = 'QUALITY'");
            statement.execute("DELETE FROM platform.business_batch WHERE code = 'BATCH_A'");
            statement.execute("DELETE FROM platform.business_period WHERE code IN ('PERIOD_A', 'PERIOD_B')");
            statement.execute("DELETE FROM platform.region WHERE code LIKE '99%'");
        }
    }

    private void assertInsertRejected(String sql) {
        assertThatThrownBy(() -> {
            try (Connection connection = DATABASE.openConnection();
                    Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
        }).isInstanceOf(SQLException.class);
    }

}
