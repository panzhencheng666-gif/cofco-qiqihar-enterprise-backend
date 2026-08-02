package com.cofco.qiqihar.graintrade.production.infrastructure;

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

class ProductionV16MasterDataTest {
    private static final ProtectedTestDatabase DATABASE = ProtectedTestDatabase.shared();

    @BeforeAll
    static void migrate() {
        DATABASE.flyway().migrate();
    }

    @Test
    void seedsOrderedChineseFactCategoryMetadataAndNoBusinessRecords() throws SQLException {
        assertThat(rows("""
                SELECT code || ':' || label || ':' || sort_order
                FROM platform.production_fact_category
                ORDER BY sort_order
                """)).containsExactly(
                        "QUALITY:质量指标:10",
                        "COST:生产成本:20",
                        "INSURANCE:农业保险:30",
                        "SUBSIDY:农业补贴:40");
        assertThat(singleLong("SELECT count(*) FROM production.production_record")).isZero();
    }

    @Test
    void seedsOnlyConfirmedDefinitionsWithUnitsAndScales() throws SQLException {
        assertThat(rows("""
                SELECT code || ':' || category || ':' || label || ':' || coalesce(unit, '-') || ':' || decimal_scale
                FROM platform.production_fact_definition
                WHERE code NOT LIKE '%\\_TEST' ESCAPE '\\'
                ORDER BY category, code
                """)).containsExactlyInAnyOrder(
                        "MOISTURE:QUALITY:水分:%:1",
                        "TEST_WEIGHT:QUALITY:容重:克/升:0",
                        "IMPURITY:QUALITY:杂质:%:1",
                        "IMPERFECT_GRAIN:QUALITY:不完善粒:%:1",
                        "MILDEW:QUALITY:霉变:%:1",
                        "PROTEIN:QUALITY:蛋白:%:1",
                        "OIL_YIELD:QUALITY:出油率:%:1",
                        "MILLING_YIELD:QUALITY:出米率:%:1",
                        "BROWN_RICE_YIELD:QUALITY:出糙率:%:1",
                        "LAND_RENT:COST:地租:元/亩:0",
                        "SEED_COST:COST:种子费用:元/亩:0",
                        "PESTICIDE_COST:COST:农药费用:元/亩:0",
                        "FERTILIZER_COST:COST:化肥费用:元/亩:0",
                        "IRRIGATION_COST:COST:灌溉费用:元/亩:0",
                        "LABOR_COST:COST:人工费用:元/亩:0",
                        "MACHINERY_COST:COST:机耕费用:元/亩:0",
                        "OTHER_COST:COST:其他成本:元/亩:0",
                        "INSURANCE_AMOUNT:INSURANCE:保险金额:元:0",
                        "SUBSIDY_AMOUNT:SUBSIDY:补贴金额:元:0");
        assertThat(singleLong("""
                SELECT count(*) FROM platform.production_fact_definition
                WHERE code = 'TOXIN'
                """)).isZero();
    }

    @Test
    void appliesQualityToThreeObjectsAndCostSupportOnlyToFarmerAndVillageCommittee()
            throws SQLException {
        for (String product : List.of("CORN", "SOYBEAN", "RICE")) {
            int qualityCount = product.equals("RICE") ? 4 : 5;
            for (String objectType : List.of(
                    "FARMER", "VILLAGE_COMMITTEE", "AGRICULTURAL_TECH_STATION")) {
                assertThat(applicableCount(product, objectType, "QUALITY")).isEqualTo(qualityCount);
            }
            for (String objectType : List.of("FARMER", "VILLAGE_COMMITTEE")) {
                assertThat(applicableCount(product, objectType, "COST")).isEqualTo(8);
                assertThat(applicableCount(product, objectType, "INSURANCE")).isOne();
                assertThat(applicableCount(product, objectType, "SUBSIDY")).isOne();
            }
            assertThat(applicableCount(product, "AGRICULTURAL_TECH_STATION", "COST")).isZero();
            assertThat(applicableCount(product, "AGRICULTURAL_TECH_STATION", "INSURANCE")).isZero();
            assertThat(applicableCount(product, "AGRICULTURAL_TECH_STATION", "SUBSIDY")).isZero();
        }
    }

    @Test
    void categoryForeignKeyRemainsExtensibleBeyondTheInitialFourGroups() throws SQLException {
        try (Connection connection = DATABASE.openConnection(); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                statement.execute("""
                        INSERT INTO platform.production_fact_category(code, label, sort_order)
                        VALUES ('EVIDENCE', '佐证材料', 50)
                        """);
                statement.execute("""
                        INSERT INTO platform.production_fact_definition
                            (code, category, label, value_type, decimal_precision, decimal_scale)
                        VALUES ('PHOTO_COUNT', 'EVIDENCE', '照片数量', 'DECIMAL', 18, 0)
                        """);
                assertThat(singleLong(connection, """
                        SELECT count(*) FROM platform.production_fact_definition
                        WHERE code = 'PHOTO_COUNT' AND category = 'EVIDENCE'
                        """)).isOne();
            } finally {
                connection.rollback();
            }
        }
    }

    private long applicableCount(String product, String objectType, String category) throws SQLException {
        return singleLong("""
                SELECT count(*)
                FROM platform.production_fact_applicability applicability
                JOIN platform.production_fact_definition definition ON definition.code = applicability.fact_code
                WHERE applicability.product_code = '%s'
                  AND applicability.object_type_code = '%s'
                  AND applicability.business_domain = 'PRODUCTION'
                  AND applicability.page_kind = 'MONITORING'
                  AND definition.category = '%s'
                """.formatted(product, objectType, category));
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

    private long singleLong(String sql) throws SQLException {
        try (Connection connection = DATABASE.openConnection();
                Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery(sql)) {
            row.next();
            return row.getLong(1);
        }
    }

    private long singleLong(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet row = statement.executeQuery(sql)) {
            row.next();
            return row.getLong(1);
        }
    }
}
