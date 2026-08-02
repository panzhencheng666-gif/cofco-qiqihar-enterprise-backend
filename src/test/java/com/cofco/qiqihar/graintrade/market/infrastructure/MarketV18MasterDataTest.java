package com.cofco.qiqihar.graintrade.market.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.cofco.qiqihar.graintrade.testsupport.ProtectedTestDatabase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class MarketV18MasterDataTest {
    private static final ProtectedTestDatabase DATABASE = ProtectedTestDatabase.shared();

    @BeforeAll
    static void migrate() { DATABASE.flyway().migrate(); }

    @Test
    void seedsOrderedChineseGroupsAndDatabaseDrivenCoreFieldsAndOptions() throws SQLException {
        assertThat(rows("""
                SELECT code || ':' || label || ':' || sort_order
                FROM platform.market_fact_category ORDER BY sort_order
                """)).containsExactly(
                        "QUALITY:质量指标:10", "PURCHASE:采购与成交:20", "SALES:销售:30",
                        "PROCESSING:加工生产:40", "INVENTORY:库存:50");
        assertThat(rows("""
                SELECT code || ':' || label || ':' || control_type || ':' || sort_order
                FROM platform.market_core_field_definition ORDER BY sort_order
                """)).containsExactly(
                        "MKT_OBJECT_TYPE:对象类型:SELECT:10", "MKT_REGION:地区:REGION_HIERARCHY:20",
                        "MKT_TRADE_DATE:交易日期:DATE:30", "MKT_TRADE_DIRECTION:买卖方向:SELECT:40",
                        "MKT_PURCHASE_BASE_PRICE:采购基础价:DECIMAL:50", "MKT_SALE_BASE_PRICE:销售基础价:DECIMAL:60",
                        "MKT_CARRIAGE_BOARD_AMOUNT:车板组成:DECIMAL:70", "MKT_PACKAGING_FORM:包装形态:SELECT:80",
                        "MKT_PACKAGING_AMOUNT:包装组成:DECIMAL:90", "MKT_FREIGHT_AMOUNT:运费组成:DECIMAL:100",
                        "MKT_ACTUAL_TRADE_PRICE:实际成交价:READONLY_DECIMAL:110");
        assertThat(rows("""
                SELECT field_code || ':' || value || ':' || label
                FROM platform.market_core_field_option ORDER BY field_code, sort_order
                """)).containsExactly(
                        "MKT_PACKAGING_FORM:BAGGED:包粮", "MKT_PACKAGING_FORM:BULK:散粮",
                        "MKT_TRADE_DIRECTION:PURCHASE:采购", "MKT_TRADE_DIRECTION:SALE:销售");
    }

    @Test
    void keepsPurchaseVolumeAndQualityForProcessorsBreedingAndFeedMill() throws SQLException {
        for (String context : List.of("CORN:DEEP_PROCESSOR", "SOYBEAN:DEEP_PROCESSOR",
                "RICE:DEEP_PROCESSOR", "CORN:BREEDING_FACTORY", "CORN:FEED_MILL", "RICE:RICE_MILL")) {
            String[] values = context.split(":");
            assertThat(singleLong("""
                    SELECT count(*) FROM platform.market_fact_applicability
                    WHERE product_code = '%s' AND object_type_code = '%s'
                      AND fact_code = 'PURCHASE_VOLUME'
                    """.formatted(values[0], values[1]))).isOne();
            assertThat(singleLong("""
                    SELECT count(*) FROM platform.market_fact_applicability a
                    JOIN platform.market_fact_definition d ON d.code = a.fact_code
                    WHERE a.product_code = '%s' AND a.object_type_code = '%s' AND d.category = 'QUALITY'
                    """.formatted(values[0], values[1]))).isPositive();
        }
    }

    private List<String> rows(String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Connection connection = DATABASE.openConnection(); Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) values.add(rows.getString(1));
        }
        return values;
    }

    private long singleLong(String sql) throws SQLException {
        try (Connection connection = DATABASE.openConnection(); Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery(sql)) {
            row.next();
            return row.getLong(1);
        }
    }
}
