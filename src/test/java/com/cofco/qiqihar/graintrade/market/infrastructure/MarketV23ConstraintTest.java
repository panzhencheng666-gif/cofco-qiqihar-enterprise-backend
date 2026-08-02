package com.cofco.qiqihar.graintrade.market.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cofco.qiqihar.graintrade.testsupport.ProtectedTestDatabase;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class MarketV23ConstraintTest {
    private static final ProtectedTestDatabase DATABASE = ProtectedTestDatabase.shared();

    @BeforeAll
    static void migrate() { DATABASE.flyway().migrate(); }

    @Test
    void definitionInsertUpdateAndDeleteCannotBypassMountMappingConsistency() {
        assertTransactionRejected(
                "SET CONSTRAINTS ALL IMMEDIATE",
                "SET CONSTRAINTS ALL DEFERRED",
                "UPDATE platform.market_core_field_definition "
                        + "SET domain_binding = 'EXTENSION', control_type = 'TEXT', "
                        + "capability = 'GENERIC', required = false, "
                        + "decimal_precision = NULL, decimal_scale = NULL "
                        + "WHERE code = 'MKT_REGION'");

        assertTransactionRejected(
                fieldDefinition("V23_LATE_DEFINITION"),
                pageMount("CORN", "V23_LATE_DEFINITION", 9301),
                "SET CONSTRAINTS ALL IMMEDIATE",
                "SET CONSTRAINTS ALL DEFERRED",
                extensionDefinition("V23_LATE_DEFINITION", 9301));

        assertTransactionRejected(
                extensionDefinition("V23_UPDATED_AWAY", 9302),
                fieldDefinition("V23_UPDATED_AWAY"),
                pageMount("CORN", "V23_UPDATED_AWAY", 9302),
                applicability("CORN", "V23_UPDATED_AWAY"),
                "SET CONSTRAINTS ALL IMMEDIATE",
                "SET CONSTRAINTS ALL DEFERRED",
                "DELETE FROM platform.market_core_field_applicability "
                        + "WHERE product_code = 'CORN' AND field_code = 'V23_UPDATED_AWAY'",
                "UPDATE platform.market_core_field_definition "
                        + "SET code = 'V23_UPDATED_AWAY_NEW' WHERE code = 'V23_UPDATED_AWAY'");

        assertTransactionRejected(
                extensionDefinition("V23_DEFINITION_DELETED", 9303),
                fieldDefinition("V23_DEFINITION_DELETED"),
                pageMount("CORN", "V23_DEFINITION_DELETED", 9303),
                applicability("CORN", "V23_DEFINITION_DELETED"),
                "SET CONSTRAINTS ALL IMMEDIATE",
                "SET CONSTRAINTS ALL DEFERRED",
                "DELETE FROM platform.market_core_field_applicability "
                        + "WHERE product_code = 'CORN' AND field_code = 'V23_DEFINITION_DELETED'",
                "DELETE FROM platform.market_core_field_definition "
                        + "WHERE code = 'V23_DEFINITION_DELETED'");
    }

    @Test
    void acceptsCompleteExtensionDefinitionsAndProductRemountsInEitherOrder() {
        assertTransactionAccepted(
                fieldDefinition("V23_MAPPING_FIRST"),
                applicability("CORN", "V23_MAPPING_FIRST"),
                extensionDefinition("V23_MAPPING_FIRST", 9304),
                pageMount("CORN", "V23_MAPPING_FIRST", 9304));

        assertTransactionAccepted(
                extensionDefinition("V23_REMAP_CHILD_FIRST", 9305),
                fieldDefinition("V23_REMAP_CHILD_FIRST"),
                pageMount("CORN", "V23_REMAP_CHILD_FIRST", 9305),
                applicability("CORN", "V23_REMAP_CHILD_FIRST"),
                "SET CONSTRAINTS ALL IMMEDIATE",
                "SET CONSTRAINTS ALL DEFERRED",
                applicability("SOYBEAN", "V23_REMAP_CHILD_FIRST"),
                "DELETE FROM platform.market_core_field_applicability "
                        + "WHERE product_code = 'CORN' AND field_code = 'V23_REMAP_CHILD_FIRST'",
                "DELETE FROM platform.page_definition_field "
                        + "WHERE product_code = 'CORN' AND field_code = 'V23_REMAP_CHILD_FIRST'",
                pageMount("SOYBEAN", "V23_REMAP_CHILD_FIRST", 9305));

        assertTransactionAccepted(
                extensionDefinition("V23_REMAP_PARENT_FIRST", 9306),
                fieldDefinition("V23_REMAP_PARENT_FIRST"),
                pageMount("CORN", "V23_REMAP_PARENT_FIRST", 9306),
                applicability("CORN", "V23_REMAP_PARENT_FIRST"),
                "SET CONSTRAINTS ALL IMMEDIATE",
                "SET CONSTRAINTS ALL DEFERRED",
                "DELETE FROM platform.page_definition_field "
                        + "WHERE product_code = 'CORN' AND field_code = 'V23_REMAP_PARENT_FIRST'",
                pageMount("SOYBEAN", "V23_REMAP_PARENT_FIRST", 9306),
                "DELETE FROM platform.market_core_field_applicability "
                        + "WHERE product_code = 'CORN' AND field_code = 'V23_REMAP_PARENT_FIRST'",
                applicability("SOYBEAN", "V23_REMAP_PARENT_FIRST"));
    }

    @Test
    void referencedFactApplicabilityCannotBeDeletedOrMoved() {
        assertTransactionRejected(
                record("v23-delete-applicability", "CORN", "FEED_MILL"),
                contextualFact(
                        "v23-delete-applicability", "CORN", "FEED_MILL", "PROCESSING_INPUT"),
                "DELETE FROM platform.market_fact_applicability "
                        + "WHERE product_code = 'CORN' AND object_type_code = 'FEED_MILL' "
                        + "AND fact_code = 'PROCESSING_INPUT'");

        assertTransactionRejected(
                record("v23-move-applicability", "CORN", "FEED_MILL"),
                contextualFact(
                        "v23-move-applicability", "CORN", "FEED_MILL", "PROCESSING_INPUT"),
                "UPDATE platform.market_fact_applicability "
                        + "SET object_type_code = 'BREEDING_FACTORY', sort_order = 999 "
                        + "WHERE product_code = 'CORN' AND object_type_code = 'FEED_MILL' "
                        + "AND fact_code = 'PROCESSING_INPUT'");
    }

    @Test
    void parentContextMayChangeBeforeFactsAreAtomicallyReplacedButInvalidFinalStateRollsBack() {
        assertTransactionAccepted(
                record("v23-legal-replace", "CORN", "FEED_MILL"),
                contextualFact(
                        "v23-legal-replace", "CORN", "FEED_MILL", "PROCESSING_INPUT"),
                "UPDATE market.market_record SET object_type_code = 'BREEDING_FACTORY' "
                        + "WHERE record_id = 'v23-legal-replace'",
                "DELETE FROM market.market_record_fact WHERE record_id = 'v23-legal-replace'",
                contextualFact(
                        "v23-legal-replace", "CORN", "BREEDING_FACTORY", "PURCHASE_VOLUME"));

        assertTransactionRejected(
                record("v23-invalid-final", "CORN", "FEED_MILL"),
                contextualFact(
                        "v23-invalid-final", "CORN", "FEED_MILL", "PROCESSING_INPUT"),
                "UPDATE market.market_record SET object_type_code = 'BREEDING_FACTORY' "
                        + "WHERE record_id = 'v23-invalid-final'");
    }

    @Test
    void concurrentApplicabilityDeleteWaitsForAnUncommittedFactAndThenFailsClosed()
            throws Exception {
        String id = "v23-concurrent-applicability";
        try (Connection setup = DATABASE.openConnection();
             Statement statement = setup.createStatement()) {
            statement.execute("""
                    INSERT INTO platform.market_fact_applicability(
                        fact_code, product_code, object_type_code, sort_order)
                    VALUES ('PROCESSING_INPUT', 'CORN', 'FEED_MILL', 70)
                    ON CONFLICT DO NOTHING
                    """);
            statement.execute(record(id, "CORN", "FEED_MILL"));
        }

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection factWriter = DATABASE.openConnection();
             Connection applicabilityWriter = DATABASE.openConnection()) {
            factWriter.setAutoCommit(false);
            applicabilityWriter.setAutoCommit(false);
            try (Statement statement = factWriter.createStatement()) {
                statement.execute(contextualFact(
                        id, "CORN", "FEED_MILL", "PROCESSING_INPUT"));
                statement.execute("SET CONSTRAINTS ALL IMMEDIATE");
            }

            Future<Throwable> deletion = executor.submit(() -> {
                try (Statement statement = applicabilityWriter.createStatement()) {
                    statement.execute("""
                            DELETE FROM platform.market_fact_applicability
                            WHERE product_code = 'CORN'
                              AND object_type_code = 'FEED_MILL'
                              AND fact_code = 'PROCESSING_INPUT'
                            """);
                    statement.execute("SET CONSTRAINTS ALL IMMEDIATE");
                    applicabilityWriter.commit();
                    return null;
                } catch (Throwable failure) {
                    return failure;
                }
            });

            assertThatThrownBy(() -> deletion.get(300, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            factWriter.commit();
            assertThat(deletion.get(5, TimeUnit.SECONDS)).isInstanceOf(SQLException.class);
            applicabilityWriter.rollback();
        } finally {
            executor.shutdownNow();
            try (Connection cleanup = DATABASE.openConnection();
                 Statement statement = cleanup.createStatement()) {
                statement.execute("DELETE FROM market.market_record WHERE record_id = '" + id + "'");
                statement.execute("""
                        INSERT INTO platform.market_fact_applicability(
                            fact_code, product_code, object_type_code, sort_order)
                        VALUES ('PROCESSING_INPUT', 'CORN', 'FEED_MILL', 70)
                        ON CONFLICT DO NOTHING
                        """);
            }
        }
    }

    private static String extensionDefinition(String code, int sortOrder) {
        return """
                INSERT INTO platform.market_core_field_definition(
                    code, label, control_type, sort_order, description,
                    domain_binding, capability, required)
                VALUES ('%s', 'V23测试扩展', 'TEXT', %d, NULL,
                        'EXTENSION', 'GENERIC', false)
                """.formatted(code, sortOrder);
    }

    private static String fieldDefinition(String code) {
        return """
                INSERT INTO platform.field_definition(code, name, value_type)
                VALUES ('%s', 'V23测试扩展', 'TEXT')
                """.formatted(code);
    }

    private static String pageMount(String product, String code, int sortOrder) {
        return """
                INSERT INTO platform.page_definition_field(
                    product_code, business_domain, page_kind, field_code, sort_order)
                VALUES ('%s', 'MARKET', 'MONITORING', '%s', %d)
                """.formatted(product, code, sortOrder);
    }

    private static String applicability(String product, String code) {
        return """
                INSERT INTO platform.market_core_field_applicability(
                    product_code, business_domain, page_kind, field_code, domain_binding)
                VALUES ('%s', 'MARKET', 'MONITORING', '%s', 'EXTENSION')
                """.formatted(product, code);
    }

    private static String record(String id, String product, String objectType) {
        return """
                INSERT INTO market.market_record(
                    record_id, product_code, object_type_code, region_code, trade_date, reported_at,
                    trade_direction, purchase_base_price, sale_base_price, carriage_board_amount,
                    packaging_amount, freight_amount, packaging_form, status_code, last_modified_by)
                VALUES ('%s', '%s', '%s', '230200', DATE '2026-08-01',
                        TIMESTAMPTZ '2026-08-02 08:00:00+08', 'PURCHASE', 2300, NULL,
                        36, 12, 72, 'BULK', 'DRAFT', 'v23-test')
                """.formatted(id, product, objectType);
    }

    private static String contextualFact(
            String id, String product, String objectType, String factCode) {
        return """
                INSERT INTO market.market_record_fact(
                    record_id, fact_code, value, product_code, object_type_code)
                VALUES ('%s', '%s', 12, '%s', '%s')
                """.formatted(id, factCode, product, objectType);
    }

    private void assertTransactionRejected(String... statements) {
        assertThatThrownBy(() -> executeTransaction(statements)).isInstanceOf(SQLException.class);
    }

    private void assertTransactionAccepted(String... statements) {
        assertThatCode(() -> executeTransaction(statements)).doesNotThrowAnyException();
    }

    private void executeTransaction(String... statements) throws SQLException {
        try (Connection connection = DATABASE.openConnection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                for (String sql : statements) statement.execute(sql);
                statement.execute("SET CONSTRAINTS ALL IMMEDIATE");
            } finally {
                connection.rollback();
            }
        }
    }
}
