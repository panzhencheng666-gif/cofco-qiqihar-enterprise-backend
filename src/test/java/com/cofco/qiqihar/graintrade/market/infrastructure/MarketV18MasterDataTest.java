package com.cofco.qiqihar.graintrade.market.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.cofco.qiqihar.graintrade.testsupport.ProtectedTestDatabase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
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
                        "MKT_TRADE_DATE:交易日期:DATE:30", "MKT_REPORTED_AT:填报时间:READONLY_DATETIME:35",
                        "MKT_TRADE_DIRECTION:买卖方向:SELECT:40",
                        "MKT_PURCHASE_BASE_PRICE:采购基础价:DECIMAL:50", "MKT_SALE_BASE_PRICE:销售基础价:DECIMAL:60",
                        "MKT_CARRIAGE_BOARD_AMOUNT:车板组成:DECIMAL:70", "MKT_PACKAGING_FORM:包装形态:SELECT:80",
                        "MKT_PACKAGING_AMOUNT:包装组成:DECIMAL:90", "MKT_FREIGHT_AMOUNT:运费组成:DECIMAL:100",
                        "MKT_SOURCE_NOTE:来源说明:TEXT:105",
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

    @Test
    void exposesReportedAtAndPriceSemanticsFromForwardOnlyMarketMasterData() throws SQLException {
        assertThat(rows("""
                SELECT code || ':' || label || ':' || control_type || ':' || sort_order
                FROM platform.market_core_field_definition
                WHERE code = 'MKT_REPORTED_AT'
                """)).containsExactly("MKT_REPORTED_AT:填报时间:READONLY_DATETIME:35");
        assertThat(rows("""
                SELECT field_code || ':' || coalesce(description, 'null')
                FROM platform.page_column_group_field
                WHERE business_domain = 'MARKET' AND page_kind = 'MONITORING'
                  AND product_code = 'CORN'
                  AND field_code IN ('MKT_PURCHASE_BASE_PRICE', 'MKT_SALE_BASE_PRICE',
                                     'MKT_ACTUAL_TRADE_PRICE', 'MKT_REPORTED_AT')
                ORDER BY sort_order
                """)).containsExactly(
                        "MKT_REPORTED_AT:null",
                        "MKT_PURCHASE_BASE_PRICE:采购基础价未包含车板、包装和运费组成",
                        "MKT_SALE_BASE_PRICE:销售基础价未包含车板、包装和运费组成",
                        "MKT_ACTUAL_TRADE_PRICE:实际成交价已包含车板、包装和运费组成");
        assertThat(singleLong("""
                SELECT count(*) FROM platform.page_definition_field
                WHERE business_domain = 'MARKET' AND page_kind = 'MONITORING'
                  AND field_code = 'MKT_REPORTED_AT'
                """)).isEqualTo(3);
        assertThat(rows("""
                SELECT code || ':' || domain_binding || ':' || capability || ':' || required
                FROM platform.market_core_field_definition
                WHERE code IN ('MKT_OBJECT_TYPE', 'MKT_SOURCE_NOTE', 'MKT_ACTUAL_TRADE_PRICE')
                ORDER BY sort_order
                """)).containsExactly(
                        "MKT_OBJECT_TYPE:OBJECT_TYPE:OBJECT_TYPE_CONTEXT:true",
                        "MKT_SOURCE_NOTE:EXTENSION:GENERIC:false",
                        "MKT_ACTUAL_TRADE_PRICE:ACTUAL_TRADE_PRICE:ACTUAL_TRADE_PRICE:false");
    }

    @Test
    void matchesTheVersionedV22MarketContractSnapshot() throws Exception {
        List<String> snapshot = rows("""
                SELECT line FROM (
                    SELECT concat_ws('|', 'FACT', applicability.product_code,
                        applicability.object_type_code, applicability.fact_code,
                        definition.category, definition.label, definition.unit,
                        definition.decimal_precision, definition.decimal_scale,
                        applicability.sort_order) AS line
                    FROM platform.market_fact_applicability applicability
                    JOIN platform.market_fact_definition definition
                      ON definition.code = applicability.fact_code
                    UNION ALL
                    SELECT concat_ws('|', 'CORE', page_field.product_code,
                        definition.code, definition.label, definition.control_type,
                        coalesce(definition.unit, ''),
                        coalesce(definition.decimal_precision::text, ''),
                        coalesce(definition.decimal_scale::text, ''), definition.sort_order,
                        definition.domain_binding, definition.capability, definition.required,
                        page_field.sort_order)
                    FROM platform.page_definition_field page_field
                    JOIN platform.market_core_field_definition definition
                      ON definition.code = page_field.field_code
                    WHERE page_field.business_domain = 'MARKET'
                      AND page_field.page_kind = 'MONITORING'
                    UNION ALL
                    SELECT concat_ws('|', 'OBJECT', applicability.product_code,
                        object_type.code, object_type.name, object_type.sort_order)
                    FROM platform.product_object_type applicability
                    JOIN platform.object_type object_type
                      ON object_type.code = applicability.object_type_code
                    WHERE object_type.business_domain = 'MARKET'
                ) contract_snapshot
                ORDER BY line
                """);
        String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest((String.join("\n", snapshot) + "\n")
                        .getBytes(StandardCharsets.UTF_8)));
        String expected = Files.readString(Path.of(
                "src/test/resources/contracts/market-v22-contract.sha256")).trim();

        assertThat(rows("""
                SELECT code || ':' || label || ':' || decimal_scale
                FROM platform.market_fact_definition
                WHERE code IN ('PROTEIN', 'TEST_WEIGHT') ORDER BY code
                """)).containsExactly("PROTEIN:蛋白:1", "TEST_WEIGHT:容重:0");
        assertThat(singleLong("""
                SELECT count(*) FROM platform.market_core_field_definition
                WHERE code = 'MKT_CORN_SOURCE_NOTE'
                """)).isZero();
        assertThat(rows("""
                SELECT code || ':' || coalesce(description, 'null')
                FROM platform.market_core_field_definition
                WHERE code = 'MKT_SOURCE_NOTE'
                """)).containsExactly("MKT_SOURCE_NOTE:null");
        assertThat(actual).isEqualTo(expected);
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
