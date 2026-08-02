package com.cofco.qiqihar.graintrade.market.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cofco.qiqihar.graintrade.testsupport.ProtectedTestDatabase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class MarketV21ConstraintTest {
    private static final ProtectedTestDatabase DATABASE = ProtectedTestDatabase.shared();

    @BeforeAll
    static void migrate() { DATABASE.flyway().migrate(); }

    @AfterEach
    void deleteFixtures() throws SQLException {
        execute("DELETE FROM market.market_record WHERE record_id LIKE 'v21-%'");
        execute("DELETE FROM platform.market_core_field_definition WHERE code LIKE 'V21_%'");
    }

    @Test
    void mountsTheCornExtensionOnlyOnCornAndEnforcesRecordProductAndExtensionBinding() throws SQLException {
        assertThat(rows("""
                SELECT product_code FROM platform.page_definition_field
                WHERE business_domain = 'MARKET' AND page_kind = 'MONITORING'
                  AND field_code = 'MKT_CORN_SOURCE_NOTE'
                ORDER BY product_code
                """)).containsExactly("CORN");
        insertRecord("v21-corn", "CORN", "FEED_MILL");
        insertRecord("v21-soy", "SOYBEAN", "DEEP_PROCESSOR");

        execute("""
                INSERT INTO market.market_record_core_value
                    (record_id, product_code, field_code, domain_binding, value)
                VALUES ('v21-corn', 'CORN', 'MKT_CORN_SOURCE_NOTE', 'EXTENSION', '合法扩展')
                """);
        assertThat(rows("""
                SELECT value FROM market.market_record_core_value
                WHERE record_id = 'v21-corn' AND field_code = 'MKT_CORN_SOURCE_NOTE'
                """)).containsExactly("合法扩展");

        assertInsertRejected("""
                INSERT INTO market.market_record_core_value
                    (record_id, product_code, field_code, domain_binding, value)
                VALUES ('v21-soy', 'SOYBEAN', 'MKT_CORN_SOURCE_NOTE', 'EXTENSION', '越权扩展')
                """);
        assertInsertRejected("""
                INSERT INTO market.market_record_core_value
                    (record_id, product_code, field_code, domain_binding, value)
                VALUES ('v21-corn', 'SOYBEAN', 'MKT_SOURCE_NOTE', 'EXTENSION', '伪造产品')
                """);
        assertInsertRejected("""
                INSERT INTO market.market_record_core_value
                    (record_id, product_code, field_code, domain_binding, value)
                VALUES ('v21-corn', 'CORN', 'MKT_ACTUAL_TRADE_PRICE',
                        'ACTUAL_TRADE_PRICE', '1')
                """);
        assertInsertRejected("""
                INSERT INTO market.market_record_core_value
                    (record_id, product_code, field_code, domain_binding, value)
                VALUES ('v21-corn', 'CORN', 'MKT_REGION', 'REGION', '230200')
                """);
    }

    @Test
    void rejectsDuplicateTypedBindingsAndUnsupportedMetadataCombinations() {
        assertInsertRejected("""
                INSERT INTO platform.market_core_field_definition
                    (code, label, control_type, sort_order, domain_binding, capability, required)
                VALUES ('V21_DUP_REGION', '重复地区', 'REGION_HIERARCHY', 9101,
                        'REGION', 'GENERIC', true)
                """);
        assertInsertRejected("""
                INSERT INTO platform.market_core_field_definition
                    (code, label, control_type, sort_order, domain_binding, capability, required)
                VALUES ('V21_REGION_EXTENSION', '非法地区扩展', 'REGION_HIERARCHY', 9102,
                        'EXTENSION', 'GENERIC', false)
                """);
        assertInsertRejected("""
                INSERT INTO platform.market_core_field_definition
                    (code, label, control_type, sort_order, domain_binding, capability, required)
                VALUES ('V21_BAD_CAPABILITY', '非法能力', 'TEXT', 9103,
                        'EXTENSION', 'PRICE_COMPONENT', false)
                """);
    }

    private void insertRecord(String id, String product, String objectType) throws SQLException {
        execute("""
                INSERT INTO market.market_record(
                    record_id, product_code, object_type_code, region_code, trade_date, reported_at,
                    trade_direction, purchase_base_price, sale_base_price, carriage_board_amount,
                    packaging_amount, freight_amount, packaging_form, status_code, last_modified_by)
                VALUES ('%s', '%s', '%s', '230200', DATE '2026-08-01',
                        TIMESTAMPTZ '2026-08-02 08:00:00+08', 'PURCHASE', 2300, NULL,
                        36, 12, 72, 'BULK', 'DRAFT', 'v21-test')
                """.formatted(id, product, objectType));
    }

    private List<String> rows(String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Connection connection = DATABASE.openConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) values.add(rows.getString(1));
        }
        return values;
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = DATABASE.openConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private void assertInsertRejected(String sql) {
        assertThatThrownBy(() -> execute(sql)).isInstanceOf(SQLException.class);
    }
}
