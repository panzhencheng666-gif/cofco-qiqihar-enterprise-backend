package com.cofco.qiqihar.graintrade.masterdata.infrastructure;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FlywayMigrationReplayTest {

    private static final String URL = environment(
            "QIQIHAR_TEST_DB_URL",
            "jdbc:postgresql://127.0.0.1:5432/qiqihar_enterprise_test");
    private static final String USERNAME = environment("QIQIHAR_TEST_DB_USERNAME", System.getenv("USER"));
    private static final String PASSWORD = environment("QIQIHAR_TEST_DB_PASSWORD", "");
    private static final String[] BUSINESS_SCHEMAS = {
        "platform", "production", "market", "logistics", "supply", "reporting", "workflow", "overview"
    };

    @BeforeAll
    static void resetDedicatedTestDatabase() throws SQLException {
        if (!URL.matches("jdbc:postgresql://[^/]+/qiqihar_enterprise_test(?:\\?.*)?")) {
            throw new IllegalStateException("Migration replay may only reset qiqihar_enterprise_test");
        }
        try (Connection connection = DriverManager.getConnection(URL, USERNAME, PASSWORD);
                Statement statement = connection.createStatement()) {
            for (String schema : BUSINESS_SCHEMAS) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
            statement.execute("DROP TABLE IF EXISTS public.flyway_schema_history");
        }
    }

    @Test
    void appliesVersionedMigrationsOnceAndKeepsChecksumsAndDataStableOnSecondStartup() throws SQLException {
        Flyway firstStartup = flyway();
        MigrateResult firstResult = firstStartup.migrate();

        assertThat(firstResult.migrationsExecuted).isEqualTo(3);
        assertThat(existingBusinessSchemas()).containsExactlyInAnyOrder(BUSINESS_SCHEMAS);
        Map<String, Integer> firstChecksums = migrationChecksums();
        Map<String, Long> firstCounts = masterDataCounts();

        MigrateResult secondResult = flyway().migrate();

        assertThat(secondResult.migrationsExecuted).isZero();
        assertThat(migrationChecksums()).isEqualTo(firstChecksums);
        assertThat(masterDataCounts()).isEqualTo(firstCounts);
        assertThat(firstCounts).containsEntry("region", 29L)
                .containsEntry("product", 3L)
                .containsEntry("cultivar", 2L)
                .containsEntry("object_type", 10L)
                .containsEntry("page_definition_field", 9L);
    }

    private Flyway flyway() {
        return Flyway.configure().dataSource(URL, USERNAME, PASSWORD).load();
    }

    private Map<String, Integer> migrationChecksums() throws SQLException {
        Map<String, Integer> checksums = new LinkedHashMap<>();
        try (Connection connection = DriverManager.getConnection(URL, USERNAME, PASSWORD);
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
            try (Connection connection = DriverManager.getConnection(URL, USERNAME, PASSWORD);
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
        try (Connection connection = DriverManager.getConnection(URL, USERNAME, PASSWORD);
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

    private static String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
