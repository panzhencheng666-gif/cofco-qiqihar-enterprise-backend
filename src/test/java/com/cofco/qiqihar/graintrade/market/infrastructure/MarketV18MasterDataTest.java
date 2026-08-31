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
                        "QUALITY:质量指标:10", "PURCHASE:采购业务:20", "SALES:销售:30",
                        "PROCESSING:加工生产:40", "INVENTORY:库存:50");
        assertThat(rows("""
                SELECT code || ':' || label || ':' || control_type || ':' || sort_order
                FROM platform.market_core_field_definition ORDER BY sort_order
                """)).containsExactly(
                        "MKT_OBJECT_TYPE:对象类型:SELECT:10", "MKT_REGION:地区:REGION_HIERARCHY:20",
                        "MKT_TRADE_DATE:交易日期:DATE:30", "MKT_REPORTED_AT:最后保存时间（兼容字段）:READONLY_DATETIME:35",
                        "MKT_PURCHASE_BASE_PRICE:对象采购价格:DECIMAL:50", "MKT_SALE_BASE_PRICE:对象销售价格:DECIMAL:60",
                        "MKT_CARRIAGE_BOARD_AMOUNT:车板组成:DECIMAL:70", "MKT_PACKAGING_FORM:包装形态:SELECT:80",
                        "MKT_PACKAGING_AMOUNT:包装组成:DECIMAL:90", "MKT_FREIGHT_AMOUNT:运费组成:DECIMAL:100",
                        "MKT_SOURCE_NOTE:来源说明:TEXT:105",
                        "MKT_REPORTER_NAME:填报人:TEXT:120", "MKT_SURVEYOR_NAME:调研人:TEXT:121",
                        "MKT_SURVEYOR_PHONE:调研人联系方式:TEXT:122",
                        "MKT_SAMPLE_CONTACT:填报对象/客户联系方式:TEXT:123",
                        "MKT_SAMPLE_LATITUDE:样本点纬度:DECIMAL:124",
                        "MKT_SAMPLE_LONGITUDE:样本点经度:DECIMAL:125",
                        "MKT_SAMPLE_NAME:填报对象/客户名称:TEXT:126",
                        "MKT_CULTIVAR_NAME:具体品种:TEXT:127",
                        "MKT_SAMPLE_SUBJECT_CODE:样本主体唯一标识:TEXT:128",
                        "AGRI_INPUT_SEED_SALES_VOLUME:种子销售量:DECIMAL:130",
                        "AGRI_INPUT_SEED_RETAIL_PRICE:种子零售价:DECIMAL:131",
                        "AGRI_INPUT_SUPPLY_STATUS:供货状态:SELECT:132",
                        "AGRI_INPUT_PLANTING_INTENTION_TREND:种植意向趋势:SELECT:133",
                        "MKT_INVENTORY_HOLDER_CODE:库存填报主体唯一标识:TEXT:141",
                        "MKT_INVENTORY_OWNERSHIP_TYPE:库存权属:SELECT:142",
                        "MKT_STORAGE_REGION_CODE:库存存放地区:REGION_HIERARCHY:143",
                        "MKT_CARGO_OWNER_CODE:货主唯一标识:TEXT:144",
                        "MKT_INVENTORY_CUTOFF_DATE:库存统计截止日:DATE:145",
                        "MKT_INVENTORY_POLICY_ATTRIBUTE:库存政策属性:SELECT:146",
                        "MKT_REPORTER_PHONE:历史填报人联系方式（停用）:TEXT:1121");
        assertThat(rows("""
                SELECT field_code || ':' || value || ':' || label
                FROM platform.market_core_field_option ORDER BY field_code, sort_order
                """)).containsExactly(
                        "AGRI_INPUT_PLANTING_INTENTION_TREND:INCREASE:增加",
                        "AGRI_INPUT_PLANTING_INTENTION_TREND:STABLE:持平",
                        "AGRI_INPUT_PLANTING_INTENTION_TREND:DECREASE:减少",
                        "AGRI_INPUT_SUPPLY_STATUS:SUFFICIENT:充足",
                        "AGRI_INPUT_SUPPLY_STATUS:NORMAL:正常",
                        "AGRI_INPUT_SUPPLY_STATUS:TIGHT:偏紧",
                        "AGRI_INPUT_SUPPLY_STATUS:OUT_OF_STOCK:缺货",
                        "MKT_INVENTORY_OWNERSHIP_TYPE:OWNED:自有库存",
                        "MKT_INVENTORY_OWNERSHIP_TYPE:CUSTODIAL:代储库存",
                        "MKT_INVENTORY_POLICY_ATTRIBUTE:COMMERCIAL:商品库存",
                        "MKT_INVENTORY_POLICY_ATTRIBUTE:POLICY:政策性库存",
                        "MKT_INVENTORY_POLICY_ATTRIBUTE:POLICY_AND_COMMERCIAL:政策与商品复合属性",
                        "MKT_PACKAGING_FORM:BAGGED:包粮", "MKT_PACKAGING_FORM:BULK:散粮");
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
                """)).containsExactly("MKT_REPORTED_AT:最后保存时间（兼容字段）:READONLY_DATETIME:35");
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
                        "MKT_PURCHASE_BASE_PRICE:被调查对象当前对外采购报价",
                        "MKT_SALE_BASE_PRICE:被调查对象当前对外销售报价");
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
                        "MKT_SOURCE_NOTE:EXTENSION:GENERIC:false");
    }

    @Test
    void matchesTheVersionedV23MarketContractSnapshot() throws Exception {
        String actual = contractHash();
        String expected = Files.readString(Path.of(
                "src/test/resources/contracts/market-v23-contract.sha256")).trim();

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
        assertThat(rows("""
                SELECT DISTINCT field_code
                FROM platform.page_definition_field
                WHERE business_domain = 'MARKET' AND page_kind = 'MONITORING'
                  AND field_code IN (
                    'MKT_SAMPLE_SUBJECT_CODE','MKT_INVENTORY_HOLDER_CODE',
                    'MKT_INVENTORY_OWNERSHIP_TYPE','MKT_STORAGE_REGION_CODE',
                    'MKT_CARGO_OWNER_CODE','MKT_INVENTORY_CUTOFF_DATE',
                    'MKT_INVENTORY_POLICY_ATTRIBUTE')
                ORDER BY field_code
                """)).isEmpty();
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void canonicalHashDetectsDescriptionOptionAndCategoryMetadataDrift() throws Exception {
        assertContractMutationChangesHash("""
                UPDATE platform.market_core_field_definition
                SET description = '描述漂移'
                WHERE code = 'MKT_PURCHASE_BASE_PRICE'
                """);
        assertContractMutationChangesHash("""
                UPDATE platform.market_core_field_option
                SET label = '散装漂移'
                WHERE field_code = 'MKT_PACKAGING_FORM' AND value = 'BULK'
                """);
        assertContractMutationChangesHash("""
                UPDATE platform.market_core_field_option
                SET sort_order = 999
                WHERE field_code = 'MKT_PACKAGING_FORM' AND value = 'BULK'
                """);
        assertContractMutationChangesHash("""
                UPDATE platform.market_fact_category
                SET label = '质量漂移'
                WHERE code = 'QUALITY'
                """);
        assertContractMutationChangesHash("""
                UPDATE platform.market_fact_category
                SET sort_order = 999
                WHERE code = 'QUALITY'
                """);
    }

    private String contractHash() throws Exception {
        try (Connection connection = DATABASE.openConnection()) {
            return contractHash(connection);
        }
    }

    private String contractHash(Connection connection) throws Exception {
        List<String> snapshot = rows(connection, """
                SELECT line FROM (
                    SELECT concat_ws('|', 'CATEGORY', category.code,
                        category.label, category.sort_order) AS line
                    FROM platform.market_fact_category category
                    UNION ALL
                    SELECT concat_ws('|', 'FACT', applicability.product_code,
                        applicability.object_type_code, applicability.fact_code,
                        definition.category, definition.label, 'DECIMAL',
                        coalesce(definition.unit, ''), '',
                        definition.decimal_precision, definition.decimal_scale,
                        applicability.sort_order) AS line
                    FROM platform.market_fact_applicability applicability
                    JOIN platform.market_fact_definition definition
                      ON definition.code = applicability.fact_code
                    UNION ALL
                    SELECT concat_ws('|', 'CORE', page_field.product_code,
                        definition.code, definition.label, definition.control_type,
                        coalesce(definition.unit, ''),
                        coalesce(definition.description, ''),
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
                    SELECT concat_ws('|', 'CORE_OPTION', page_field.product_code,
                        option.field_code, option.value, option.label, option.sort_order)
                    FROM platform.page_definition_field page_field
                    JOIN platform.market_core_field_option option
                      ON option.field_code = page_field.field_code
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
                ORDER BY line COLLATE "C"
                """);
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest((String.join("\n", snapshot) + "\n")
                        .getBytes(StandardCharsets.UTF_8)));
    }

    private void assertContractMutationChangesHash(String mutation) throws Exception {
        try (Connection connection = DATABASE.openConnection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                String baseline = contractHash(connection);
                statement.executeUpdate(mutation);
                assertThat(contractHash(connection)).isNotEqualTo(baseline);
            } finally {
                connection.rollback();
            }
        }
    }

    private List<String> rows(String sql) throws SQLException {
        try (Connection connection = DATABASE.openConnection()) {
            return rows(connection, sql);
        }
    }

    private List<String> rows(Connection connection, String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
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
