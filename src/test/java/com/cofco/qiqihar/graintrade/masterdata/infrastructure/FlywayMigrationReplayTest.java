package com.cofco.qiqihar.graintrade.masterdata.infrastructure;

import com.cofco.qiqihar.graintrade.testsupport.ProtectedTestDatabase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
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
        MigrateResult versionFiveResult = flywayToVersionFive().migrate();

        assertThat(versionFiveResult.migrationsExecuted).isEqualTo(5);
        assertThat(sha256(Path.of(
                        "src/main/resources/db/migration/V5__create_business_page_definition_kernel.sql")))
                .isEqualTo("b7969f210f73ffd3654b33444691f0fba32474eab51a5d4a7faea0043a214404");
        assertThat(existingBusinessSchemas()).containsExactlyInAnyOrder(BUSINESS_SCHEMAS);
        Map<String, Integer> versionFiveChecksums = migrationChecksums();
        assertThat(versionFiveChecksums)
                .containsExactly(
                        Map.entry("1", 578287895),
                        Map.entry("2", -1029775028),
                        Map.entry("3", -1102740881),
                        Map.entry("4", 2052234299),
                        Map.entry("5", -1133431193));

        MigrateResult versionEightResult = DATABASE.flywayToVersion("8").migrate();
        assertThat(versionEightResult.migrationsExecuted).isEqualTo(3);
        insertMarketUpgradeFixture();

        MigrateResult upgradeResult = flyway().migrate();
        assertThat(upgradeResult.migrationsExecuted).isEqualTo(2);
        assertThat(migrationChecksums()).containsAllEntriesOf(versionFiveChecksums);
        assertThat(marketRecordCount()).isOne();
        deleteMarketUpgradeFixture();
        assertThat(marketRecordCount()).isZero();

        Map<String, Long> firstCounts = masterDataCounts();

        MigrateResult secondResult = flyway().migrate();

        assertThat(secondResult.migrationsExecuted).isZero();
        assertThat(migrationChecksums()).hasSize(10);
        assertThat(masterDataCounts()).isEqualTo(firstCounts);
        assertThat(firstCounts).containsEntry("region", 29L)
                .containsEntry("product", 3L)
                .containsEntry("cultivar", 2L)
                .containsEntry("object_type", 10L)
                .containsEntry("page_definition_field", 18L);
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

    @Test
    @Order(3)
    void enforcesPagePresentationPaginationAndUniqueFieldPlacement() throws SQLException {
        assertTransactionRejected("""
                DELETE FROM platform.page_pagination
                WHERE product_code = 'RICE'
                  AND business_domain = 'MARKET'
                  AND page_kind = 'QUALITY'
                """);
        assertTransactionRejected("""
                DELETE FROM platform.page_size_option
                WHERE product_code = 'RICE'
                  AND business_domain = 'MARKET'
                  AND page_kind = 'QUALITY'
                  AND page_size = 20
                """);
        assertTransactionRejected(
                """
                INSERT INTO platform.page_column_group
                    (product_code, business_domain, page_kind, code, label, sort_order)
                VALUES ('RICE', 'MARKET', 'QUALITY', 'SECOND', '第二组', 20)
                """,
                """
                INSERT INTO platform.page_column_group_field
                    (product_code, business_domain, page_kind, group_code, field_code, sort_order)
                VALUES ('RICE', 'MARKET', 'QUALITY', 'SECOND', 'MOISTURE', 10)
                """);

        assertThat(tableComment("platform.page_pagination"))
                .contains("Task 3 platform interaction configuration")
                .contains("not business master data")
                .contains("not sourced from the golden screenshot");
    }

    @Test
    @Order(4)
    void rejectsMovingPaginationWhenTheOldPresentationWouldBecomeIncomplete() throws SQLException {
        insertPaginationMoveFixtures();
        try {
            assertThatThrownBy(() -> {
                try (Connection connection = DATABASE.openConnection();
                        Statement statement = connection.createStatement()) {
                    connection.setAutoCommit(false);
                    try {
                        statement.execute("SET CONSTRAINTS ALL DEFERRED");
                        statement.execute("""
                                DELETE FROM platform.page_size_option
                                WHERE product_code = 'CORN'
                                  AND business_domain = 'MARKET'
                                  AND page_kind = 'MOVE_NEW'
                                """);
                        statement.execute("""
                                DELETE FROM platform.page_pagination
                                WHERE product_code = 'CORN'
                                  AND business_domain = 'MARKET'
                                  AND page_kind = 'MOVE_NEW'
                                """);
                        statement.execute("""
                                UPDATE platform.page_pagination
                                SET page_kind = 'MOVE_NEW'
                                WHERE product_code = 'CORN'
                                  AND business_domain = 'MARKET'
                                  AND page_kind = 'MOVE_OLD'
                                """);
                        statement.execute("""
                                UPDATE platform.page_size_option
                                SET page_kind = 'MOVE_NEW'
                                WHERE product_code = 'CORN'
                                  AND business_domain = 'MARKET'
                                  AND page_kind = 'MOVE_OLD'
                                """);
                        connection.commit();
                    } finally {
                        connection.rollback();
                    }
                }
            }).isInstanceOf(SQLException.class)
                    .hasMessageContaining("Every page presentation requires pagination configuration");

            assertThat(paginationCount("MOVE_OLD")).isOne();
            assertThat(paginationCount("MOVE_NEW")).isOne();
        } finally {
            deletePaginationMoveFixtures();
        }
    }

    @Test
    @Order(5)
    void rejectsNonScalarMarketProjectionValuesAtTheDatabaseBoundary() {
        assertInsertRejected("""
                INSERT INTO market.market_record_projection
                    (record_id, product_code, business_domain, page_kind, observed_at, values)
                VALUES ('invalid-boolean', 'SOYBEAN', 'MARKET', 'QUALITY', now(),
                        '{"active":true}'::jsonb)
                """);
        assertInsertRejected("""
                INSERT INTO market.market_record_projection
                    (record_id, product_code, business_domain, page_kind, observed_at, values)
                VALUES ('invalid-array', 'SOYBEAN', 'MARKET', 'QUALITY', now(),
                        '{"tags":["a"]}'::jsonb)
                """);
        assertInsertRejected("""
                INSERT INTO market.market_record_projection
                    (record_id, product_code, business_domain, page_kind, observed_at, values)
                VALUES ('invalid-object', 'SOYBEAN', 'MARKET', 'QUALITY', now(),
                        '{"nested":{"score":1}}'::jsonb)
                """);
    }

    @Test
    @Order(6)
    void supportsOneStrongProductIndependentPageIdentityAndWorkflowStartsEmpty() throws SQLException {
        try (Connection connection = DATABASE.openConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO platform.page_definition
                        (product_code, business_domain, page_kind)
                    VALUES (NULL, 'WORKFLOW', 'MIGRATION_TEST')
                    """);
            assertThatThrownBy(() -> statement.execute("""
                            INSERT INTO platform.page_definition
                                (product_code, business_domain, page_kind)
                            VALUES (NULL, 'WORKFLOW', 'MIGRATION_TEST')
                            """))
                    .isInstanceOf(SQLException.class);

            try (ResultSet rows = statement.executeQuery("""
                    SELECT count(*)
                    FROM workflow.work_item_status
                    WHERE code IN ('TO_FILL', 'TO_REVIEW', 'RETURNED', 'EXCEPTION')
                    """)) {
                rows.next();
                assertThat(rows.getLong(1)).isEqualTo(4);
            }
            try (ResultSet rows = statement.executeQuery(
                    "SELECT count(*) FROM workflow.work_item_status")) {
                rows.next();
                assertThat(rows.getLong(1)).isEqualTo(4);
            }
            try (ResultSet rows = statement.executeQuery("SELECT count(*) FROM workflow.work_item")) {
                rows.next();
                assertThat(rows.getLong(1)).isZero();
            }
        } finally {
            try (Connection connection = DATABASE.openConnection();
                    Statement statement = connection.createStatement()) {
                statement.execute("""
                        DELETE FROM platform.page_definition
                        WHERE product_code IS NULL
                          AND business_domain = 'WORKFLOW'
                          AND page_kind = 'MIGRATION_TEST'
                        """);
            }
        }
    }

    @Test
    @Order(7)
    void enforcesPendingStatusAndCompletedScopeStateAtTheDatabaseBoundary() throws SQLException {
        try (Connection connection = DATABASE.openConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO platform.business_period
                        (code, name, starts_on, ends_on, sort_order)
                    VALUES ('WORK_SCOPE_PERIOD', '工作流约束期间', DATE '2026-08-01', DATE '2026-08-31', 9990)
                    """);
            statement.execute("INSERT INTO workflow.workflow_node (code, label) VALUES ('WORK_SCOPE_NODE', '约束节点')");
            statement.execute("""
                    INSERT INTO workflow.responsible_party
                        (party_type, external_code, display_name)
                    VALUES ('USER', 'WORK_SCOPE_USER', '约束责任人')
                    """);

            assertInsertRejected("""
                    INSERT INTO workflow.work_item
                        (task_name, business_domain, region_code, product_code,
                         business_period_code, due_at, workflow_node_id, status_code,
                         responsible_party_id, completed_at)
                    SELECT '非法待办', 'MARKET', '230202', 'SOYBEAN', 'WORK_SCOPE_PERIOD', now(),
                           node_id, NULL, responsible_party_id, NULL
                    FROM workflow.workflow_node CROSS JOIN workflow.responsible_party
                    WHERE code = 'WORK_SCOPE_NODE' AND external_code = 'WORK_SCOPE_USER'
                    """);
            assertInsertRejected("""
                    INSERT INTO workflow.work_item
                        (task_name, business_domain, region_code, product_code,
                         business_period_code, due_at, workflow_node_id, status_code,
                         responsible_party_id, completed_at)
                    SELECT '非法已办', 'MARKET', '230202', 'SOYBEAN', 'WORK_SCOPE_PERIOD', now(),
                           node_id, 'TO_FILL', responsible_party_id, now()
                    FROM workflow.workflow_node CROSS JOIN workflow.responsible_party
                    WHERE code = 'WORK_SCOPE_NODE' AND external_code = 'WORK_SCOPE_USER'
                    """);
        } finally {
            try (Connection connection = DATABASE.openConnection();
                    Statement statement = connection.createStatement()) {
                statement.execute("DELETE FROM workflow.work_item WHERE task_name LIKE '非法%'");
                statement.execute("DELETE FROM workflow.responsible_party WHERE external_code = 'WORK_SCOPE_USER'");
                statement.execute("DELETE FROM workflow.workflow_node WHERE code = 'WORK_SCOPE_NODE'");
                statement.execute("DELETE FROM platform.business_period WHERE code = 'WORK_SCOPE_PERIOD'");
            }
        }
    }

    private Flyway flyway() {
        return DATABASE.flyway();
    }

    private long marketRecordCount() throws SQLException {
        try (Connection connection = DATABASE.openConnection();
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(
                        "SELECT count(*) FROM market.market_record_projection")) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private Flyway flywayToVersionFive() {
        return DATABASE.flywayToVersion("5");
    }

    private void insertMarketUpgradeFixture() throws SQLException {
        try (Connection connection = DATABASE.openConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO market.market_record_projection
                        (record_id, product_code, business_domain, page_kind, observed_at, values)
                    VALUES ('upgrade-preserved', 'SOYBEAN', 'MARKET', 'QUALITY', now(),
                            '{"subjectName":"升级保留记录"}'::jsonb)
                    """);
        }
    }

    private void deleteMarketUpgradeFixture() throws SQLException {
        try (Connection connection = DATABASE.openConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM market.market_record_projection WHERE record_id = 'upgrade-preserved'");
        }
    }

    private String sha256(Path path) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        } catch (Exception exception) {
            throw new IllegalStateException("Could not hash migration", exception);
        }
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

    private void insertPaginationMoveFixtures() throws SQLException {
        try (Connection connection = DATABASE.openConnection();
                Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("SET CONSTRAINTS ALL DEFERRED");
            statement.execute("""
                    INSERT INTO platform.page_definition
                        (product_code, business_domain, page_kind)
                    VALUES
                        ('CORN', 'MARKET', 'MOVE_OLD'),
                        ('CORN', 'MARKET', 'MOVE_NEW')
                    """);
            statement.execute("""
                    INSERT INTO platform.page_presentation
                        (product_code, business_domain, page_kind, title)
                    VALUES
                        ('CORN', 'MARKET', 'MOVE_OLD', '旧分页移动测试'),
                        ('CORN', 'MARKET', 'MOVE_NEW', '新分页移动测试')
                    """);
            statement.execute("""
                    INSERT INTO platform.page_pagination
                        (product_code, business_domain, page_kind, default_page_size)
                    VALUES
                        ('CORN', 'MARKET', 'MOVE_OLD', 20),
                        ('CORN', 'MARKET', 'MOVE_NEW', 20)
                    """);
            statement.execute("""
                    INSERT INTO platform.page_size_option
                        (product_code, business_domain, page_kind, page_size, sort_order)
                    VALUES
                        ('CORN', 'MARKET', 'MOVE_OLD', 20, 10),
                        ('CORN', 'MARKET', 'MOVE_NEW', 20, 10)
                    """);
            connection.commit();
        }
    }

    private void deletePaginationMoveFixtures() throws SQLException {
        try (Connection connection = DATABASE.openConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("""
                    DELETE FROM platform.page_definition
                    WHERE product_code = 'CORN'
                      AND business_domain = 'MARKET'
                      AND page_kind IN ('MOVE_OLD', 'MOVE_NEW')
                    """);
        }
    }

    private long paginationCount(String pageKind) throws SQLException {
        try (Connection connection = DATABASE.openConnection();
                java.sql.PreparedStatement statement = connection.prepareStatement("""
                        SELECT count(*)
                        FROM platform.page_pagination
                        WHERE product_code = 'CORN'
                          AND business_domain = 'MARKET'
                          AND page_kind = ?
                        """)) {
            statement.setString(1, pageKind);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
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

    private void assertTransactionRejected(String... sqlStatements) {
        assertThatThrownBy(() -> {
            try (Connection connection = DATABASE.openConnection();
                    Statement statement = connection.createStatement()) {
                connection.setAutoCommit(false);
                try {
                    for (String sql : sqlStatements) {
                        statement.execute(sql);
                    }
                    connection.commit();
                } finally {
                    connection.rollback();
                }
            }
        }).isInstanceOf(SQLException.class);
    }

    private String tableComment(String qualifiedTableName) throws SQLException {
        try (Connection connection = DATABASE.openConnection();
                Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery(
                        "SELECT obj_description('" + qualifiedTableName + "'::regclass)")) {
            row.next();
            return row.getString(1);
        }
    }

}
