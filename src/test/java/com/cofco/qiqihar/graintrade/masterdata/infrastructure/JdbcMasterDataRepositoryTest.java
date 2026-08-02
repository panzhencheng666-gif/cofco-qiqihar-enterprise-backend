package com.cofco.qiqihar.graintrade.masterdata.infrastructure;

import com.cofco.qiqihar.graintrade.masterdata.domain.FieldDefinition;
import com.cofco.qiqihar.graintrade.masterdata.domain.BusinessBatch;
import com.cofco.qiqihar.graintrade.masterdata.domain.ObjectType;
import com.cofco.qiqihar.graintrade.masterdata.domain.Region;
import com.cofco.qiqihar.graintrade.testsupport.ProtectedTestDatabase;
import java.util.List;
import java.sql.Connection;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcMasterDataRepositoryTest {

    private static final ProtectedTestDatabase DATABASE = ProtectedTestDatabase.shared();

    private final JdbcMasterDataRepository repository = new JdbcMasterDataRepository(dataSource());

    @BeforeAll
    static void migrateTestDatabase() {
        DATABASE.flyway().migrate();
    }

    @Test
    void loadsConfirmedProductsCultivarsAndRegionHierarchy() {
        assertThat(repository.findProducts())
                .extracting(product -> product.name())
                .containsExactly("玉米", "大豆", "稻谷");
        assertThat(repository.findCultivarsByProductCode("SOYBEAN"))
                .extracting(cultivar -> cultivar.name())
                .containsExactly("黑农84", "东生22");

        List<Region> regions = repository.findRegions();
        assertThat(regions).hasSize(29);
        assertThat(regions).filteredOn(region -> region.parentCode() == null)
                .extracting(Region::name)
                .containsExactly("齐齐哈尔市", "黑河市", "呼伦贝尔市");
        assertThat(regions).filteredOn(region -> "150700".equals(region.parentCode()))
                .extracting(Region::name)
                .containsExactly("阿荣旗", "莫力达瓦达斡尔族自治旗", "鄂伦春自治旗", "扎兰屯市");
    }

    @Test
    void loadsOnlyRootsOrTheDirectChildrenOfOneRegion() {
        assertThat(repository.findRegionChildren(null)).extracting(Region::name)
                .containsExactly("齐齐哈尔市", "黑河市", "呼伦贝尔市");
        assertThat(repository.findRegionChildren("230200")).extracting(Region::name)
                .containsExactly(
                        "龙沙区", "建华区", "铁锋区", "昂昂溪区", "富拉尔基区", "碾子山区",
                        "梅里斯达斡尔族区", "龙江县", "依安县", "泰来县", "甘南县", "富裕县",
                        "克山县", "克东县", "拜泉县", "讷河市");
        assertThat(repository.findRegionChildren("230202")).isEmpty();
    }

    @Test
    void filtersMarketAndProductionObjectTypesByExplicitProductApplicability() {
        assertThat(names(repository.findObjectTypes("SOYBEAN", "MARKET")))
                .containsExactly("贸易商", "深加工", "批发市场", "承储企业")
                .doesNotContain("米厂");
        assertThat(names(repository.findObjectTypes("RICE", "MARKET")))
                .containsExactly("贸易商", "深加工", "批发市场", "承储企业", "米厂");
        assertThat(names(repository.findObjectTypes("CORN", "MARKET")))
                .containsExactly("贸易商", "深加工", "批发市场", "承储企业", "养殖厂", "饲料厂");
        assertThat(names(repository.findObjectTypes("SOYBEAN", "PRODUCTION")))
                .containsExactly("农户", "村委会", "农技站");
    }

    @Test
    void loadsProductSpecificQualityPageDefinitionsInConfiguredOrder() {
        List<FieldDefinition> riceFields = repository
                .findPageDefinition("RICE", "MARKET", "QUALITY").orElseThrow().fields();
        List<FieldDefinition> soybeanFields = repository
                .findPageDefinition("SOYBEAN", "MARKET", "QUALITY").orElseThrow().fields();

        assertThat(riceFields).extracting(FieldDefinition::name)
                .containsExactly("水分", "出米率", "出糙率", "杂质");
        assertThat(soybeanFields).extracting(FieldDefinition::name)
                .containsExactly("蛋白", "出油率", "不完善粒", "水分", "杂质");
        assertThat(repository.findPageDefinition("CORN", "MARKET", "QUALITY")).isEmpty();
        assertThat(repository.findBusinessPeriods()).isEmpty();
    }

    @Test
    void loadsOnlyRequestedPeriodBatchesInStableOrder() throws Exception {
        insertBusinessBatchFixtures();
        try {
            List<BusinessBatch> batches = repository.findBusinessBatchesByPeriodCode("PERIOD_2026");

            assertThat(batches).extracting(BusinessBatch::name)
                    .containsExactly("第一批", "第二批");
            assertThat(batches).extracting(BusinessBatch::businessPeriodCode)
                    .containsOnly("PERIOD_2026");
            assertThat(repository.findBusinessBatchesByPeriodCode("PERIOD_2025")).isEmpty();
        } finally {
            deleteBusinessBatchFixtures();
        }
    }

    private List<String> names(List<ObjectType> objectTypes) {
        return objectTypes.stream().map(ObjectType::name).toList();
    }

    private static DataSource dataSource() {
        return DATABASE.dataSource();
    }

    private void insertBusinessBatchFixtures() throws Exception {
        try (Connection connection = DATABASE.openConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO platform.business_period
                        (code, name, starts_on, ends_on, sort_order)
                    VALUES ('PERIOD_2026', '2026业务期', DATE '2026-01-01', DATE '2026-12-31', 10)
                    """);
            statement.execute("""
                    INSERT INTO platform.business_batch
                        (code, name, business_period_code, sort_order)
                    VALUES
                        ('BATCH_2', '第二批', 'PERIOD_2026', 20),
                        ('BATCH_1', '第一批', 'PERIOD_2026', 10)
                    """);
        }
    }

    private void deleteBusinessBatchFixtures() throws Exception {
        try (Connection connection = DATABASE.openConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM platform.business_batch WHERE business_period_code = 'PERIOD_2026'");
            statement.execute("DELETE FROM platform.business_period WHERE code = 'PERIOD_2026'");
        }
    }

}
