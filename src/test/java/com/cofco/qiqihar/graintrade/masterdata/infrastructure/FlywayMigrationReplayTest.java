package com.cofco.qiqihar.graintrade.masterdata.infrastructure;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.market.application.MarketMonitoringDraft;
import com.cofco.qiqihar.graintrade.market.application.MarketMonitoringService;
import com.cofco.qiqihar.graintrade.market.domain.MarketRecordQuery;
import com.cofco.qiqihar.graintrade.shared.application.ConflictException;
import com.cofco.qiqihar.graintrade.testsupport.ProtectedTestDatabase;
import com.cofco.qiqihar.graintrade.testsupport.ProtectedTestDatabaseConfiguration;
import com.cofco.qiqihar.graintrade.shared.domain.BusinessPageKey;
import com.cofco.qiqihar.graintrade.shared.infrastructure.JdbcPageDefinitionRepository;
import java.math.BigDecimal;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

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
            statement.execute("DROP SCHEMA IF EXISTS evidence CASCADE");
            statement.execute("DROP SCHEMA IF EXISTS registry CASCADE");
            statement.execute("DROP TABLE IF EXISTS public.flyway_schema_history");
        }
    }

    @AfterAll
    static void restoreSharedTestFixtures() {
        ProtectedTestDatabaseConfiguration.provisionSecurityTestSubjects(
                JdbcClient.create(DATABASE.dataSource()));
    }

    @Test
    @Order(1)
    void appliesVersionedMigrationsOnceAndKeepsChecksumsAndDataStableOnSecondStartup()
            throws Exception {
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

        MigrateResult versionTenResult = DATABASE.flywayToVersion("10").migrate();
        assertThat(versionTenResult.migrationsExecuted).isEqualTo(2);
        assertThat(migrationChecksums()).containsAllEntriesOf(versionFiveChecksums);
        assertThat(marketRecordCount()).isOne();

        assertFrozenMarketMigrationChecksums();
        MigrateResult versionTwentyResult = DATABASE.flywayToVersion("20").migrate();
        assertThat(versionTwentyResult.migrationsExecuted).isEqualTo(10);
        insertVersionTwentyCoreValueFixture();

        MigrateResult versionTwentyOneResult = DATABASE.flywayToVersion("21").migrate();
        assertThat(versionTwentyOneResult.migrationsExecuted).isOne();
        assertVersionTwentyCoreValuePreserved();
        insertVersionTwentyOneWitnessConflictFixture();

        assertThatThrownBy(() -> DATABASE.flywayToVersion("22").migrate())
                .hasMessageContaining("Cannot migrate MKT_CORN_SOURCE_NOTE")
                .hasMessageContaining("already has MKT_SOURCE_NOTE");
        assertThat(currentMigrationVersion()).isEqualTo("21");
        assertVersionTwentyOneWitnessConflictPreserved();
        removeVersionTwentyOneWitnessConflict();

        MigrateResult versionTwentyTwoResult = DATABASE.flywayToVersion("22").migrate();
        assertThat(versionTwentyTwoResult.migrationsExecuted).isOne();
        assertVersionTwentyCoreValuePreserved();
        assertVersionTwentyOneWitnessMigrated();
        assertThat(witnessDefinitionCount()).isZero();
        assertThat(marketRecordCount()).isOne();

        insertVersionTwentyTwoUnmountedExtensionCorruption();
        assertThatThrownBy(() -> DATABASE.flywayToVersion("23").migrate())
                .hasMessageContaining("V23 preflight extension invariant failed")
                .hasMessageContaining("V23_REPLAY_UNMOUNTED");
        assertThat(currentMigrationVersion()).isEqualTo("22");
        assertVersionTwentyCoreValuePreserved();
        deleteVersionTwentyTwoUnmountedExtensionCorruption();

        insertVersionTwentyTwoInapplicableFactCorruption();
        assertThatThrownBy(() -> DATABASE.flywayToVersion("23").migrate())
                .hasMessageContaining("V23 preflight fact applicability failed")
                .hasMessageContaining("SALES_VOLUME");
        assertThat(currentMigrationVersion()).isEqualTo("22");
        assertVersionTwentyCoreValuePreserved();
        deleteVersionTwentyTwoInapplicableFactCorruption();

        deleteVersionTwentyTwoTypedDefinitionFixture();
        MigrateResult versionTwentyThreeResult = DATABASE.flywayToVersion("23").migrate();
        assertThat(versionTwentyThreeResult.migrationsExecuted).isOne();
        assertVersionTwentyCoreValuePreserved();

        assertThatThrownBy(() -> DATABASE.flywayToVersion("24").migrate())
                .hasMessageContaining("V24 preflight market page field source invariant failed")
                .hasMessageContaining("MKT_REGION");
        assertThat(currentMigrationVersion()).isEqualTo("23");
        assertThat(marketRegionCoreDefinitionCount()).isZero();
        assertThat(marketRegionMonitoringMountCount()).isEqualTo(3);
        assertVersionTwentyCoreValuePreserved();
        restoreVersionTwentyTwoTypedDefinitionFixture();

        int expectedVersionedMigrationCount = versionedMigrationFileCount();
        MigrateResult versionTwentyFourResult = flyway().migrate();
        assertThat(versionTwentyFourResult.migrationsExecuted).isEqualTo(expectedVersionedMigrationCount - 23);
        assertVersionTwentyFourDefinitionGraphGuards();
        assertThat(queryLong("SELECT count(*) FROM logistics.route_event")).isZero();
        assertThat(queryLong("SELECT count(*) FROM logistics.logistics_node")).isZero();
        assertThat(queryLong("SELECT count(*) FROM supply.source_release")).isZero();
        assertThat(queryLong("SELECT count(*) FROM supply.calculation_run")).isZero();
        assertThat(queryLong("SELECT count(*) FROM platform.page_definition WHERE business_domain IN ('LOGISTICS','SUPPLY')")).isEqualTo(6);
        assertThat(queryString("SELECT difference_expression FROM supply.formula_version WHERE active"))
                .isEqualTo("SURVEYED_ENDING_INVENTORY - ADOPTED_ENDING_INVENTORY");
        assertThat(queryLong("""
                SELECT COALESCE(SUM(ST_NRings(geometry)-ST_NumGeometries(geometry)),0)
                  FROM overview.monitoring_scope_boundary_render
                """)).as("the overall map boundary must not contain unassigned holes").isZero();
        assertThat(queryString("""
                SELECT has_table_privilege('qiqihar_enterprise_runtime',
                         'overview.sample_point_query_source','SELECT')::text
                """))
                .as("the recreated overview sample projection must remain readable by runtime")
                .isEqualTo("true");
        provisionMigrationReplaySecuritySubject();
        exerciseVersionTwentyFixtureThroughFormalService();

        deleteVersionTwentyOneWitnessFixture();
        deleteVersionTwentyCoreValueFixture();
        deleteMarketUpgradeFixture();
        assertThat(marketRecordCount()).isZero();

        Map<String, Long> firstCounts = masterDataCounts();

        MigrateResult secondResult = flyway().migrate();

        assertThat(secondResult.migrationsExecuted).isZero();
        assertThat(migrationChecksums()).hasSize(expectedVersionedMigrationCount);
        assertThat(masterDataCounts()).isEqualTo(firstCounts);
        assertThat(firstCounts).containsEntry("region", 37L)
                .containsEntry("product", 3L)
                .containsEntry("cultivar", 2L)
                .containsEntry("object_type", 13L)
                .containsEntry("page_definition_field", 228L)
                .containsEntry("production_fact_category", 5L)
                .containsEntry("production_fact_definition", 34L)
                .containsEntry("production_fact_applicability", 231L);
    }

    private void assertFrozenMarketMigrationChecksums() {
        assertThat(sha256(Path.of(
                        "src/main/resources/db/migration/V17__create_normalized_market_monitoring.sql")))
                .isEqualTo("d33fd96f416c3362c562ed716a5296fa2d506c317cc1161cd85a238a869e5ab3");
        assertThat(sha256(Path.of(
                        "src/main/resources/db/migration/V18__complete_market_form_and_price_composition.sql")))
                .isEqualTo("06fc9bf97a30d8e9db8a1fb546d54e6daee239478e3437a45d1c086c39efd2ae");
        assertThat(sha256(Path.of(
                        "src/main/resources/db/migration/V19__expose_market_reporting_time_and_price_semantics.sql")))
                .isEqualTo("c7dd9c6d4064ebfc947b359260f4be8a0023b72fdcdb550c41e83cdaa2438a7c");
        assertThat(sha256(Path.of(
                        "src/main/resources/db/migration/V20__make_market_core_values_metadata_driven.sql")))
                .isEqualTo("b300decdfe59730f5f0325034be3637fafc5e9c3b3c25a4e31d9054d7d1347e5");
        assertThat(sha256(Path.of(
                        "src/main/resources/db/migration/V21__enforce_market_core_field_contracts.sql")))
                .isEqualTo("b8446a51c15fac0c4de3f358b78c2595b0494ede809ff279270c9f9d27763cad");
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
    void rejectsMovingPaginationIdentityEvenInsideACoherentTransaction() throws SQLException {
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
                    .hasMessageContaining("Page identity and context are immutable");

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
                        (code, name, starts_on, ends_on, sort_order, marketing_year_code)
                    VALUES ('WORK_SCOPE_PERIOD', '工作流约束期间', DATE '2026-08-01', DATE '2026-08-31', 9990, '2026/27')
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

    @Test
    @Order(8)
    void makesPageIdentityAndLegacyContextImmutableWhileAllowingPresentationContentUpdates()
            throws SQLException {
        assertUpdateRejected("""
                UPDATE platform.page_definition
                SET page_kind = 'WORK_ITEMS_MOVED'
                WHERE product_code IS NULL
                  AND business_domain = 'WORKFLOW'
                  AND page_kind = 'WORK_ITEMS'
                """);
        assertUpdateRejected("""
                UPDATE platform.page_definition
                SET page_definition_id = page_definition_id + 100000
                WHERE product_code IS NULL
                  AND business_domain = 'WORKFLOW'
                  AND page_kind = 'WORK_ITEMS'
                """);
        assertUpdateRejected("""
                UPDATE platform.page_presentation
                SET business_domain = 'MARKET'
                WHERE product_code IS NULL
                  AND business_domain = 'WORKFLOW'
                  AND page_kind = 'WORK_ITEMS'
                """);
        assertUpdateRejected("""
                UPDATE platform.page_presentation
                SET page_definition_id = page_definition_id + 100000
                WHERE product_code IS NULL
                  AND business_domain = 'WORKFLOW'
                  AND page_kind = 'WORK_ITEMS'
                """);
        assertUpdateRejected("""
                UPDATE platform.page_filter_definition
                SET product_code = 'SOYBEAN'
                WHERE product_code IS NULL
                  AND business_domain = 'WORKFLOW'
                  AND page_kind = 'WORK_ITEMS'
                  AND code = 'status'
                """);
        assertUpdateRejected("""
                UPDATE platform.page_filter_definition
                SET page_presentation_id = page_presentation_id + 100000
                WHERE product_code IS NULL
                  AND business_domain = 'WORKFLOW'
                  AND page_kind = 'WORK_ITEMS'
                  AND code = 'status'
                """);

        try (Connection connection = DATABASE.openConnection();
                Statement statement = connection.createStatement()) {
            assertThat(statement.executeUpdate("""
                    UPDATE platform.page_presentation
                    SET title = '任务列表（更新测试）'
                    WHERE product_code IS NULL
                      AND business_domain = 'WORKFLOW'
                      AND page_kind = 'WORK_ITEMS'
                    """)).isOne();
            try {
                var definition = new JdbcPageDefinitionRepository(DATABASE.dataSource())
                        .find(new BusinessPageKey("WORKFLOW", "WORK_ITEMS", null))
                        .orElseThrow();
                assertThat(definition.title()).isEqualTo("任务列表（更新测试）");
                assertThat(definition.filters()).hasSize(4);
                assertThat(definition.columnGroups().getFirst().fields()).hasSize(9);
            } finally {
                statement.executeUpdate("""
                        UPDATE platform.page_presentation
                        SET title = '任务列表'
                        WHERE product_code IS NULL
                          AND business_domain = 'WORKFLOW'
                          AND page_kind = 'WORK_ITEMS'
                        """);
            }
        }
    }

    private Flyway flyway() {
        return DATABASE.flyway();
    }

    private int versionedMigrationFileCount() throws java.io.IOException {
        try (var files = Files.list(Path.of("src/main/resources/db/migration"))) {
            return (int) files
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.matches("V\\d+(?:_\\d+)*__.+\\.sql"))
                    .count();
        }
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

    private void insertVersionTwentyCoreValueFixture() throws SQLException {
        try (Connection connection = DATABASE.openConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO market.market_record(
                        record_id, product_code, object_type_code, region_code, trade_date,
                        reported_at, purchase_base_price, sale_base_price, trade_direction,
                        carriage_board_amount, packaging_amount, freight_amount, packaging_form,
                        status_code, return_reason, last_modified_by)
                    VALUES ('v20-core-upgrade', 'CORN', 'FEED_MILL', '230200', DATE '2026-08-01',
                        TIMESTAMPTZ '2026-08-01 09:00:00+08', 2300, NULL, 'PURCHASE',
                        36, 12, 72, 'BULK', 'DRAFT', NULL, 'migration-replay')
                    """);
            statement.execute("""
                    INSERT INTO market.market_record_core_value(record_id, field_code, value)
                    VALUES ('v20-core-upgrade', 'MKT_SOURCE_NOTE', 'V20保留来源')
                    """);
        }
    }

    private void assertVersionTwentyCoreValuePreserved() throws SQLException {
        try (Connection connection = DATABASE.openConnection();
                Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery("""
                        SELECT value, product_code, domain_binding
                        FROM market.market_record_core_value
                        WHERE record_id = 'v20-core-upgrade' AND field_code = 'MKT_SOURCE_NOTE'
                        """)) {
            assertThat(row.next()).isTrue();
            assertThat(row.getString("value")).isEqualTo("V20保留来源");
            assertThat(row.getString("product_code")).isEqualTo("CORN");
            assertThat(row.getString("domain_binding")).isEqualTo("EXTENSION");
        }
    }

    private void insertVersionTwentyOneWitnessConflictFixture() throws SQLException {
        try (Connection connection = DATABASE.openConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO market.market_record(
                        record_id, product_code, object_type_code, region_code, trade_date,
                        reported_at, purchase_base_price, sale_base_price, trade_direction,
                        carriage_board_amount, packaging_amount, freight_amount, packaging_form,
                        status_code, return_reason, last_modified_by)
                    VALUES ('v21-witness-upgrade', 'CORN', 'FEED_MILL', '230200', DATE '2026-08-01',
                        TIMESTAMPTZ '2026-08-01 10:00:00+08', 2300, NULL, 'PURCHASE',
                        36, 12, 72, 'BULK', 'DRAFT', NULL, 'migration-replay')
                    """);
            statement.execute("""
                    INSERT INTO market.market_record_core_value(
                        record_id, product_code, field_code, domain_binding, value)
                    VALUES
                        ('v21-witness-upgrade', 'CORN', 'MKT_CORN_SOURCE_NOTE',
                         'EXTENSION', 'V21见证来源'),
                        ('v21-witness-upgrade', 'CORN', 'MKT_SOURCE_NOTE',
                         'EXTENSION', 'V21冲突来源')
                    """);
        }
    }

    private void assertVersionTwentyOneWitnessConflictPreserved() throws SQLException {
        assertThat(coreValueCount(
                "v21-witness-upgrade", "MKT_CORN_SOURCE_NOTE", "V21见证来源")).isOne();
        assertThat(coreValueCount(
                "v21-witness-upgrade", "MKT_SOURCE_NOTE", "V21冲突来源")).isOne();
        assertThat(witnessDefinitionCount()).isOne();
    }

    private void removeVersionTwentyOneWitnessConflict() throws SQLException {
        try (Connection connection = DATABASE.openConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("""
                    DELETE FROM market.market_record_core_value
                    WHERE record_id = 'v21-witness-upgrade'
                      AND field_code = 'MKT_SOURCE_NOTE'
                    """);
        }
    }

    private void assertVersionTwentyOneWitnessMigrated() throws SQLException {
        assertThat(coreValueCount(
                "v21-witness-upgrade", "MKT_SOURCE_NOTE", "V21见证来源")).isOne();
        assertThat(coreValueCount(
                "v21-witness-upgrade", "MKT_CORN_SOURCE_NOTE", "V21见证来源")).isZero();
    }

    private long coreValueCount(String recordId, String fieldCode, String value) throws SQLException {
        try (Connection connection = DATABASE.openConnection();
                var statement = connection.prepareStatement("""
                        SELECT count(*) FROM market.market_record_core_value
                        WHERE record_id = ? AND field_code = ? AND value = ?
                        """)) {
            statement.setString(1, recordId);
            statement.setString(2, fieldCode);
            statement.setString(3, value);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private void insertVersionTwentyTwoUnmountedExtensionCorruption() throws SQLException {
        try (Connection connection = DATABASE.openConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO platform.market_core_field_definition(
                        code, label, control_type, sort_order, description,
                        domain_binding, capability, required)
                    VALUES ('V23_REPLAY_UNMOUNTED', 'V23升级脏挂载', 'TEXT', 9310, NULL,
                            'EXTENSION', 'GENERIC', false)
                    """);
            statement.execute("""
                    INSERT INTO platform.field_definition(code, name, value_type)
                    VALUES ('V23_REPLAY_UNMOUNTED', 'V23升级脏挂载', 'TEXT')
                    """);
            statement.execute("""
                    ALTER TABLE platform.page_definition_field
                    DISABLE TRIGGER market_extension_page_mount_consistency
                    """);
            statement.execute("""
                    INSERT INTO platform.page_definition_field(
                        product_code, business_domain, page_kind, field_code, sort_order)
                    VALUES ('CORN', 'MARKET', 'MONITORING', 'V23_REPLAY_UNMOUNTED', 9310)
                    """);
            statement.execute("""
                    ALTER TABLE platform.page_definition_field
                    ENABLE TRIGGER market_extension_page_mount_consistency
                    """);
        }
    }

    private void deleteVersionTwentyTwoUnmountedExtensionCorruption() throws SQLException {
        try (Connection connection = DATABASE.openConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("""
                    DELETE FROM platform.page_definition_field
                    WHERE product_code = 'CORN' AND business_domain = 'MARKET'
                      AND page_kind = 'MONITORING' AND field_code = 'V23_REPLAY_UNMOUNTED'
                    """);
            statement.execute("DELETE FROM platform.field_definition WHERE code = 'V23_REPLAY_UNMOUNTED'");
            statement.execute("""
                    DELETE FROM platform.market_core_field_definition
                    WHERE code = 'V23_REPLAY_UNMOUNTED'
                    """);
        }
    }

    private void insertVersionTwentyTwoInapplicableFactCorruption() throws SQLException {
        try (Connection connection = DATABASE.openConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("""
                    ALTER TABLE market.market_record_fact
                    DISABLE TRIGGER market_fact_applicability
                    """);
            statement.execute("""
                    INSERT INTO market.market_record_fact(record_id, fact_code, value)
                    VALUES ('v20-core-upgrade', 'SALES_VOLUME', 3)
                    """);
            statement.execute("""
                    ALTER TABLE market.market_record_fact
                    ENABLE TRIGGER market_fact_applicability
                    """);
        }
    }

    private void deleteVersionTwentyTwoInapplicableFactCorruption() throws SQLException {
        try (Connection connection = DATABASE.openConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("""
                    DELETE FROM market.market_record_fact
                    WHERE record_id = 'v20-core-upgrade' AND fact_code = 'SALES_VOLUME'
                    """);
        }
    }

    private void deleteVersionTwentyTwoTypedDefinitionFixture() throws SQLException {
        try (Connection connection = DATABASE.openConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("""
                    DELETE FROM platform.market_core_field_definition
                    WHERE code = 'MKT_REGION'
                    """);
        }
    }

    private void restoreVersionTwentyTwoTypedDefinitionFixture() throws SQLException {
        try (Connection connection = DATABASE.openConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO platform.market_core_field_definition(
                        code, label, control_type, unit, decimal_precision, decimal_scale,
                        sort_order, description, domain_binding, capability, required)
                    VALUES ('MKT_REGION', '地区', 'REGION_HIERARCHY', NULL, NULL, NULL,
                            20, NULL, 'REGION', 'GENERIC', true)
                    """);
        }
    }

    private void assertVersionTwentyFourDefinitionGraphGuards() throws SQLException {
        assertThat(count("""
                SELECT count(*)
                FROM platform.market_monitoring_projection_field_definition
                WHERE field_code = 'MKT_STATUS' AND projection_kind = 'RECORD_STATUS'
                  AND required_on_page
                """)).isOne();

        assertTransactionRejected("""
                DELETE FROM platform.page_definition_field
                WHERE product_code = 'CORN' AND business_domain = 'MARKET'
                  AND page_kind = 'MONITORING' AND field_code = 'MKT_REGION'
                """);
        assertTransactionRejected("""
                DELETE FROM platform.market_core_field_definition
                WHERE code = 'MKT_REGION'
                """);
        assertTransactionRejected(
                """
                INSERT INTO platform.field_definition(code, name, value_type)
                VALUES ('V24_FACT_SOURCE', 'V24事实来源', 'DECIMAL')
                """,
                """
                INSERT INTO platform.market_fact_definition(
                    code, category, label, unit, decimal_precision, decimal_scale)
                VALUES ('V24_FACT_SOURCE', 'QUALITY', 'V24事实来源', NULL, 18, 4)
                """,
                """
                INSERT INTO platform.page_definition_field(
                    product_code, business_domain, page_kind, field_code, sort_order)
                VALUES ('CORN', 'MARKET', 'MONITORING', 'V24_FACT_SOURCE', 9410)
                """,
                "SET CONSTRAINTS ALL IMMEDIATE",
                "SET CONSTRAINTS ALL DEFERRED",
                "DELETE FROM platform.market_fact_definition WHERE code = 'V24_FACT_SOURCE'");
        assertTransactionRejected("""
                DELETE FROM platform.market_monitoring_projection_field_definition
                WHERE field_code = 'MKT_STATUS'
                """);
    }

    private long marketRegionCoreDefinitionCount() throws SQLException {
        return count("""
                SELECT count(*) FROM platform.market_core_field_definition
                WHERE code = 'MKT_REGION'
                """);
    }

    private long marketRegionMonitoringMountCount() throws SQLException {
        return count("""
                SELECT count(*) FROM platform.page_definition_field
                WHERE business_domain = 'MARKET' AND page_kind = 'MONITORING'
                  AND field_code = 'MKT_REGION'
                """);
    }

    private long count(String sql) throws SQLException {
        try (Connection connection = DATABASE.openConnection();
                Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery(sql)) {
            row.next();
            return row.getLong(1);
        }
    }

    private String currentMigrationVersion() throws SQLException {
        try (Connection connection = DATABASE.openConnection();
                Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery("""
                        SELECT version FROM public.flyway_schema_history
                        WHERE success ORDER BY installed_rank DESC LIMIT 1
                        """)) {
            row.next();
            return row.getString(1);
        }
    }

    private void provisionMigrationReplaySecuritySubject() throws SQLException {
        try (Connection connection = DATABASE.openConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO overview.administrative_boundary(
                        region_code,geometry,source_name,source_url,source_revision,
                        source_license,geometry_sha256)
                    VALUES ('230200',
                      ST_Multi(ST_Buffer(ST_SetSRID(ST_MakePoint(123.9182,47.3543),4326),0.01)),
                      '迁移回放测试边界','https://example.invalid/migration-replay-boundary',
                      'migration-replay-v1','测试专用',
                      encode(sha256(ST_AsBinary(ST_Multi(ST_Buffer(
                        ST_SetSRID(ST_MakePoint(123.9182,47.3543),4326),0.01)))),'hex'))
                    ON CONFLICT (region_code) DO NOTHING
                    """);
            statement.execute("""
                    INSERT INTO platform.work_unit(code,name,sort_order)
                    VALUES ('MIGRATION_TEST','迁移回放测试工作单位',9999)
                    """);
            statement.execute("""
                    INSERT INTO platform.work_unit_region_scope(work_unit_code,region_code)
                    SELECT 'MIGRATION_TEST',code FROM platform.region
                    """);
            statement.execute("""
                    INSERT INTO platform.security_user(subject_id,display_name,work_unit_code)
                    VALUES ('migration-replay-service','迁移回放服务','MIGRATION_TEST')
                    """);
            statement.execute("""
                    INSERT INTO platform.security_user_role(subject_id,role_code)
                    VALUES ('migration-replay-service','SYSTEM_ADMIN')
                    """);
            statement.execute("""
                    INSERT INTO platform.security_user_region_scope(subject_id,region_code)
                    SELECT 'migration-replay-service',code FROM platform.region
                    """);
        }
    }

    private void exerciseVersionTwentyFixtureThroughFormalService() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setUserPrincipal(() -> "migration-replay-service");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(
                        GrainTradeApplication.class, ProtectedTestDatabaseConfiguration.class)
                .properties("spring.main.web-application-type=none")
                .run(DATABASE.springApplicationArguments())) {
            MarketMonitoringService service = context.getBean(MarketMonitoringService.class);
            assertThat(service.detail("v20-core-upgrade").coreValues())
                    .containsEntry("MKT_SOURCE_NOTE", "V20保留来源");

            var modified = service.save(
                    "v20-core-upgrade", 0, versionTwentyDraft("V23服务修改来源"));
            assertThat(modified.record().version()).isOne();
            assertThat(modified.coreValues())
                    .containsEntry("MKT_SOURCE_NOTE", "V23服务修改来源")
                    .containsEntry("MKT_REPORTER_NAME", "迁移回放服务")
                    .containsEntry("MKT_SAMPLE_LONGITUDE", "123.9182000");

            var cleared = service.save("v20-core-upgrade", 1, versionTwentyDraft(null));
            assertThat(cleared.record().version()).isEqualTo(2);
            assertThat(cleared.coreValues()).containsEntry("MKT_SOURCE_NOTE", null);
            assertThatThrownBy(() -> service.save(
                    "v20-core-upgrade", 1, versionTwentyDraft("陈旧覆盖")))
                    .isInstanceOf(ConflictException.class);
            assertThat(service.detail("v20-core-upgrade").coreValues())
                    .containsEntry("MKT_SOURCE_NOTE", null);

            assertThat(service.list(new MarketRecordQuery(
                    "CORN", "MONITORING", 0, 20, Map.of())).items())
                    .noneMatch(item -> item.id().equals("v20-core-upgrade"));
            assertThat(service.detail("v20-core-upgrade").record().version()).isEqualTo(2);
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    private MarketMonitoringDraft versionTwentyDraft(String sourceNote) {
        Map<String, String> coreValues = new LinkedHashMap<>();
        coreValues.put("MKT_OBJECT_TYPE", "FEED_MILL");
        coreValues.put("MKT_REGION", "230200");
        coreValues.put("MKT_TRADE_DATE", "2026-08-01");
        coreValues.put("MKT_PURCHASE_BASE_PRICE", "2300");
        coreValues.put("MKT_CARRIAGE_BOARD_AMOUNT", "36");
        coreValues.put("MKT_PACKAGING_FORM", "BULK");
        coreValues.put("MKT_PACKAGING_AMOUNT", "12");
        coreValues.put("MKT_FREIGHT_AMOUNT", "72");
        coreValues.put("MKT_REPORTER_NAME", "迁移回放填报员");
        coreValues.put("MKT_SURVEYOR_NAME", "王雷");
        coreValues.put("MKT_SURVEYOR_PHONE", "13800000000");
        coreValues.put("MKT_SAMPLE_NAME", "迁移回放样本企业");
        coreValues.put("MKT_SAMPLE_CONTACT", "13900000000");
        coreValues.put("MKT_SAMPLE_LATITUDE", "47.3543");
        coreValues.put("MKT_SAMPLE_LONGITUDE", "123.9182");
        if (sourceNote != null) coreValues.put("MKT_SOURCE_NOTE", sourceNote);
        return new MarketMonitoringDraft(
                "CORN", coreValues, Map.of("PURCHASE_VOLUME", new BigDecimal("12")));
    }

    private void deleteVersionTwentyOneWitnessFixture() throws SQLException {
        try (Connection connection = DATABASE.openConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM market.market_record WHERE record_id = 'v21-witness-upgrade'");
        }
    }

    private long witnessDefinitionCount() throws SQLException {
        try (Connection connection = DATABASE.openConnection();
                Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery("""
                        SELECT count(*) FROM platform.market_core_field_definition
                        WHERE code = 'MKT_CORN_SOURCE_NOTE'
                        """)) {
            row.next();
            return row.getLong(1);
        }
    }

    private void deleteVersionTwentyCoreValueFixture() throws SQLException {
        try (Connection connection = DATABASE.openConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM market.market_record WHERE record_id = 'v20-core-upgrade'");
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
                "region", "product", "cultivar", "object_type", "page_definition_field",
                "production_fact_category", "production_fact_definition", "production_fact_applicability"
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
                        (code, name, starts_on, ends_on, sort_order, marketing_year_code)
                    VALUES
                        ('PERIOD_A', '约束测试期间A', DATE '2026-01-01', DATE '2026-06-30', 9001, '2026/27'),
                        ('PERIOD_B', '约束测试期间B', DATE '2026-07-01', DATE '2026-12-31', 9002, '2026/27')
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

    private void assertUpdateRejected(String sql) {
        assertThatThrownBy(() -> {
            try (Connection connection = DATABASE.openConnection();
                    Statement statement = connection.createStatement()) {
                statement.executeUpdate(sql);
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

    private long queryLong(String sql) throws SQLException {
        try (Connection connection = DATABASE.openConnection();
                Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery(sql)) {
            row.next();
            return row.getLong(1);
        }
    }

    private String queryString(String sql) throws SQLException {
        try (Connection connection = DATABASE.openConnection();
                Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery(sql)) {
            row.next();
            return row.getString(1);
        }
    }

}
