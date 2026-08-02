package com.cofco.qiqihar.graintrade.market.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cofco.qiqihar.graintrade.testsupport.ProtectedTestDatabase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class MarketV22ConstraintTest {
    private static final ProtectedTestDatabase DATABASE = ProtectedTestDatabase.shared();

    @BeforeAll
    static void migrate() { DATABASE.flyway().migrate(); }

    @Test
    void rejectsAParentContextChangeWhenExistingFactsAreNotApplicableAndKeepsTheRecordAtomic()
            throws SQLException {
        try (Connection connection = DATABASE.openConnection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                insertRecord(statement, "v22-invalid-context", "CORN", "FEED_MILL");
                statement.execute("""
                        INSERT INTO market.market_record_fact(record_id, fact_code, value)
                        VALUES ('v22-invalid-context', 'TEST_WEIGHT', 720)
                        """);
                Savepoint beforeContextChange = connection.setSavepoint();

                assertThatThrownBy(() -> statement.execute("""
                        UPDATE market.market_record
                        SET product_code = 'SOYBEAN', object_type_code = 'DEEP_PROCESSOR'
                        WHERE record_id = 'v22-invalid-context'
                        """)).isInstanceOf(SQLException.class);
                connection.rollback(beforeContextChange);

                assertThat(single(statement, """
                        SELECT product_code || '|' || object_type_code
                        FROM market.market_record WHERE record_id = 'v22-invalid-context'
                        """)).isEqualTo("CORN|FEED_MILL");
                assertThat(single(statement, """
                        SELECT fact_code FROM market.market_record_fact
                        WHERE record_id = 'v22-invalid-context'
                        """)).isEqualTo("TEST_WEIGHT");
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void permitsAParentContextChangeWhenEveryExistingFactRemainsApplicable() throws SQLException {
        try (Connection connection = DATABASE.openConnection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                insertRecord(statement, "v22-valid-context", "CORN", "FEED_MILL");
                statement.execute("""
                        INSERT INTO market.market_record_fact(record_id, fact_code, value)
                        VALUES ('v22-valid-context', 'MOISTURE', 14.5)
                        """);

                assertThatCode(() -> statement.execute("""
                        UPDATE market.market_record
                        SET product_code = 'SOYBEAN', object_type_code = 'DEEP_PROCESSOR'
                        WHERE record_id = 'v22-valid-context'
                        """)).doesNotThrowAnyException();
                assertThat(single(statement, """
                        SELECT product_code || '|' || object_type_code
                        FROM market.market_record WHERE record_id = 'v22-valid-context'
                        """)).isEqualTo("SOYBEAN|DEEP_PROCESSOR");
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void rejectsMissingDeletedAndCrossProductExtensionApplicabilityAtTheDeferredBoundary() {
        assertTransactionRejected(
                extensionDefinition("V22_MISSING", 9201),
                fieldDefinition("V22_MISSING"),
                pageMount("CORN", "V22_MISSING", 9201));

        assertTransactionRejected(
                extensionDefinition("V22_DELETED", 9202),
                fieldDefinition("V22_DELETED"),
                pageMount("CORN", "V22_DELETED", 9202),
                applicability("CORN", "V22_DELETED"),
                "SET CONSTRAINTS ALL IMMEDIATE",
                "SET CONSTRAINTS ALL DEFERRED",
                "DELETE FROM platform.market_core_field_applicability "
                        + "WHERE product_code = 'CORN' AND field_code = 'V22_DELETED'");

        assertTransactionRejected(
                extensionDefinition("V22_CROSS_PRODUCT", 9203),
                fieldDefinition("V22_CROSS_PRODUCT"),
                pageMount("CORN", "V22_CROSS_PRODUCT", 9203),
                pageMount("SOYBEAN", "V22_CROSS_PRODUCT", 9203),
                applicability("SOYBEAN", "V22_CROSS_PRODUCT"));
    }

    @Test
    void acceptsACompleteExtensionMountAndApplicabilityWrittenInOneTransaction() {
        assertTransactionAccepted(
                extensionDefinition("V22_COMPLETE", 9204),
                fieldDefinition("V22_COMPLETE"),
                pageMount("CORN", "V22_COMPLETE", 9204),
                applicability("CORN", "V22_COMPLETE"));
    }

    private static String extensionDefinition(String code, int sortOrder) {
        return """
                INSERT INTO platform.market_core_field_definition(
                    code, label, control_type, sort_order, description,
                    domain_binding, capability, required)
                VALUES ('%s', '测试扩展', 'TEXT', %d, NULL, 'EXTENSION', 'GENERIC', false)
                """.formatted(code, sortOrder);
    }

    private static String fieldDefinition(String code) {
        return """
                INSERT INTO platform.field_definition(code, name, value_type)
                VALUES ('%s', '测试扩展', 'TEXT')
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

    private void insertRecord(Statement statement, String id, String product, String objectType)
            throws SQLException {
        statement.execute("""
                INSERT INTO market.market_record(
                    record_id, product_code, object_type_code, region_code, trade_date, reported_at,
                    trade_direction, purchase_base_price, sale_base_price, carriage_board_amount,
                    packaging_amount, freight_amount, packaging_form, status_code, last_modified_by)
                VALUES ('%s', '%s', '%s', '230200', DATE '2026-08-01',
                        TIMESTAMPTZ '2026-08-02 08:00:00+08', 'PURCHASE', 2300, NULL,
                        36, 12, 72, 'BULK', 'DRAFT', 'v22-test')
                """.formatted(id, product, objectType));
    }

    private String single(Statement statement, String sql) throws SQLException {
        try (ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }
}
