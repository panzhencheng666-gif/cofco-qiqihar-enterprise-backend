package com.cofco.qiqihar.graintrade.analysis.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.cofco.qiqihar.graintrade.analysis.application.ObservableAnalysisScope;
import com.cofco.qiqihar.graintrade.analysis.domain.AnalysisQualityState;
import com.cofco.qiqihar.graintrade.testsupport.ProtectedTestDatabase;
import com.cofco.qiqihar.graintrade.testsupport.ProtectedTestDatabaseConfiguration;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class JdbcObservableAnalysisRepositoryTest {
    private static final String REGION = "230221";
    private static final String OTHER_REGION = "231102";
    private static final String PERIOD = "ANALYSIS-2026-08";
    private static final String PREFIX = "analysis-observable-";
    private static final UUID DUPLICATE_POINT_ONE = UUID.fromString("97000000-0000-0000-0000-000000000001");
    private static final UUID DUPLICATE_POINT_TWO = UUID.fromString("97000000-0000-0000-0000-000000000002");
    private static final ProtectedTestDatabase DATABASE = ProtectedTestDatabase.shared();

    private final JdbcClient jdbc = JdbcClient.create(DATABASE.dataSource());
    private final JdbcObservableAnalysisRepository repository =
            new JdbcObservableAnalysisRepository(DATABASE.dataSource());

    @BeforeEach
    void insertFixtures() {
        DATABASE.flyway().migrate();
        ProtectedTestDatabaseConfiguration.provisionSecurityTestSubjects(jdbc);
        deleteFixtures();
        jdbc.sql("""
                INSERT INTO platform.business_period(
                    code,name,starts_on,ends_on,sort_order,marketing_year_code)
                VALUES(:code,'分析测试月',DATE '2026-08-01',DATE '2026-08-31',9988,'2026/27')
                """).param("code", PERIOD).update();
        jdbc.sql("""
                INSERT INTO logistics.logistics_node(node_code,node_name,node_type_code,region_code)
                VALUES(:origin,'分析起点','RAIL_NODE',:region),
                      (:destination,'分析终点','ROAD_NODE',:region)
                """).param("origin", PREFIX + "origin")
                .param("destination", PREFIX + "destination")
                .param("region", REGION).update();

        production("old", "APPROVED", 1, "400", "龙江县调查户", REGION);
        production("new", "APPROVED", 2, "500", "龙江县调查户", REGION);
        production("draft", "DRAFT", 99, "999", "草稿调查户", REGION);
        production("outside", "APPROVED", 1, "999", "北安市调查户", OTHER_REGION);
        market("approved", "APPROVED", "2500", REGION);
        market("pending", "PENDING_REVIEW", "9999", REGION);
        route("inflow", "INFLOW", "20", "APPROVED", REGION);
        route("outflow", "OUTFLOW", "15", "APPROVED", REGION);
        route("transit", "TRANSIT", "999", "APPROVED", REGION);
    }

    @AfterEach
    void deleteFixtures() {
        jdbc.sql("DELETE FROM logistics.route_fact WHERE event_id IN (SELECT event_id FROM logistics.route_event WHERE source_organization LIKE :prefix)")
                .param("prefix", PREFIX + "%").update();
        jdbc.sql("DELETE FROM logistics.route_event WHERE source_organization LIKE :prefix")
                .param("prefix", PREFIX + "%").update();
        jdbc.sql("DELETE FROM logistics.logistics_node WHERE node_code LIKE :prefix")
                .param("prefix", PREFIX + "%").update();
        jdbc.sql("DELETE FROM market.market_inventory_governance WHERE record_id LIKE :prefix")
                .param("prefix", PREFIX + "%").update();
        jdbc.sql("DELETE FROM market.market_record_fact WHERE record_id LIKE :prefix")
                .param("prefix", PREFIX + "%").update();
        jdbc.sql("DELETE FROM market.market_record_core_value WHERE record_id LIKE :prefix")
                .param("prefix", PREFIX + "%").update();
        jdbc.sql("DELETE FROM market.market_record WHERE record_id LIKE :prefix")
                .param("prefix", PREFIX + "%").update();
        jdbc.sql("DELETE FROM production.production_record_submission_metadata WHERE record_id LIKE :prefix")
                .param("prefix", PREFIX + "%").update();
        jdbc.sql("DELETE FROM production.production_record WHERE record_id LIKE :prefix")
                .param("prefix", PREFIX + "%").update();
        jdbc.sql("DELETE FROM registry.sample_point WHERE sample_point_id IN (:points)")
                .param("points", Set.of(DUPLICATE_POINT_ONE, DUPLICATE_POINT_TWO)).update();
        jdbc.sql("DELETE FROM platform.business_period WHERE code=:code")
                .param("code", PERIOD).update();
    }

    @Test
    void buildsOneSnapshotFromApprovedConfirmedFactsWithoutDoubleCountingSupersededRecords() {
        var snapshot = repository.load(
                new ObservableAnalysisScope("CORN", "230200", 2026, 8, null, null),
                Set.of("230200", REGION));

        assertThat(snapshot.qualityState()).isEqualTo(AnalysisQualityState.PARTIAL);
        assertThat(snapshot.coverage().recordCount()).isEqualTo(4);
        assertThat(snapshot.coverage().excludedRecordCount()).isGreaterThanOrEqualTo(2);
        assertThat(snapshot.production().metrics()).filteredOn(metric -> metric.code().equals("EXPECTED_OUTPUT"))
                .singleElement().satisfies(metric -> {
                    assertThat(metric.value()).isEqualTo("50.0000");
                    assertThat(metric.sourceCount()).isOne();
                });
        assertThat(snapshot.market().metrics()).filteredOn(metric -> metric.code().equals("AVERAGE_PURCHASE_PRICE"))
                .singleElement().satisfies(metric -> {
                    assertThat(metric.value()).isEqualTo("2500.0000");
                    assertThat(metric.sourceCount()).isOne();
                });
        assertThat(snapshot.logistics().metrics()).filteredOn(metric -> metric.code().equals("INFLOW_VOLUME"))
                .singleElement().satisfies(metric -> assertThat(metric.value()).isEqualTo("20.0000"));
        assertThat(snapshot.supply().inventory().productionOpeningTonnes())
                .isEqualByComparingTo("10.0000");
        assertThat(snapshot.supply().inventory().productionEndingTonnes())
                .isEqualByComparingTo("35.0000");
        assertThat(snapshot.supply().calculation().openingObservableInventoryTonnes())
                .isEqualByComparingTo("10.0000");
        assertThat(snapshot.supply().calculation().endingObservableInventoryTonnes())
                .isEqualByComparingTo("35.0000");
        assertThat(snapshot.supply().calculation().inferredOtherAbsorptionTonnes()).isNull();
        assertThat(snapshot.lineage()).extracting("recordId")
                .contains(PREFIX + "new", PREFIX + "market-approved")
                .doesNotContain(PREFIX + "old", PREFIX + "draft", PREFIX + "market-pending");
        assertThat(snapshot.analysisVersion()).startsWith("sha256:");
    }

    @Test
    void exposesOnlySourceBackedBusinessMetricsAndCalculatesDerivedIndicatorsInTheBackend() {
        jdbc.sql("""
                INSERT INTO production.production_record_submission_metadata(record_id,field_code,value)
                VALUES(:id,'PROD_HARVEST_AREA_MU','80'),
                      (:id,'PROD_AFFECTED_AREA_MU','10'),
                      (:id,'PROD_INTENDED_AREA_MU','110')
                """).param("id", PREFIX + "new").update();
        jdbc.sql("""
                INSERT INTO production.production_record_quality(record_id,quality_code,value)
                VALUES(:id,'MOISTURE',14.5)
                """).param("id", PREFIX + "new").update();
        jdbc.sql("""
                INSERT INTO production.production_record_cost(record_id,cost_code,value)
                VALUES(:id,'LAND_RENT',100),(:id,'SEED_COST',20),
                      (:id,'PESTICIDE_COST',10),(:id,'FERTILIZER_COST',30),
                      (:id,'IRRIGATION_COST',5),(:id,'LABOR_COST',40),
                      (:id,'MACHINERY_COST',50),(:id,'OTHER_COST',15)
                """).param("id", PREFIX + "new").update();
        jdbc.sql("""
                INSERT INTO production.production_record_insurance(record_id,insurance_code,value)
                VALUES(:id,'INSURANCE_AMOUNT',1000)
                """).param("id", PREFIX + "new").update();
        jdbc.sql("""
                INSERT INTO production.production_record_subsidy(record_id,subsidy_code,value)
                VALUES(:id,'SUBSIDY_AMOUNT',500)
                """).param("id", PREFIX + "new").update();
        jdbc.sql("""
                UPDATE market.market_record
                SET sale_base_price=2700,carriage_board_amount=20,
                    packaging_amount=10,freight_amount=30
                WHERE record_id=:id
                """).param("id", PREFIX + "market-approved").update();
        jdbc.sql("""
                INSERT INTO market.market_record_fact(
                    record_id,fact_code,value,product_code,object_type_code)
                VALUES(:id,'ENDING_INVENTORY',80,'CORN','TRADER'),
                      (:id,'MOISTURE',13.2,'CORN','TRADER')
                """).param("id", PREFIX + "market-approved").update();

        var snapshot = repository.load(
                new ObservableAnalysisScope("CORN", "230200", 2026, 8, null, null),
                Set.of("230200", REGION));

        assertThat(snapshot.production().metrics()).allSatisfy(metric -> {
            assertThat(metric.value()).isNotNull();
            assertThat(metric.missingReason()).isNull();
        });
        assertMetric(snapshot.production().metrics(), "EXPECTED_HARVEST_RATE", "80.0000");
        assertMetric(snapshot.production().metrics(), "AFFECTED_AREA_RATE", "10.0000");
        assertMetric(snapshot.production().metrics(), "INTENDED_AREA_CHANGE", "10.0000");
        assertMetric(snapshot.production().metrics(), "INTENDED_AREA_CHANGE_RATE", "10.0000");
        assertMetric(snapshot.production().metrics(), "QUALITY_MOISTURE_AVERAGE", "14.5000");
        assertMetric(snapshot.production().metrics(), "QUALITY_MOISTURE_MINIMUM", "14.5000");
        assertMetric(snapshot.production().metrics(), "QUALITY_MOISTURE_MAXIMUM", "14.5000");
        assertMetric(snapshot.production().metrics(), "COMPLETE_COST_PER_MU", "270.0000");
        assertMetric(snapshot.production().metrics(), "INSURANCE_AMOUNT", "1000.0000");
        assertMetric(snapshot.production().metrics(), "SUBSIDY_AMOUNT", "500.0000");

        assertThat(snapshot.market().metrics()).allSatisfy(metric -> {
            assertThat(metric.value()).isNotNull();
            assertThat(metric.missingReason()).isNull();
        });
        assertMetric(snapshot.market().metrics(), "AVERAGE_PURCHASE_SALE_SPREAD", "200.0000");
        assertMetric(snapshot.market().metrics(), "AVERAGE_CARRIAGE_BOARD_AMOUNT", "20.0000");
        assertMetric(snapshot.market().metrics(), "AVERAGE_PACKAGING_AMOUNT", "10.0000");
        assertMetric(snapshot.market().metrics(), "AVERAGE_FREIGHT_AMOUNT", "30.0000");
        assertMetric(snapshot.market().metrics(), "CURRENT_INVENTORY", "80.0000");
        assertThat(snapshot.market().metrics())
                .filteredOn(metric -> metric.code().equals("CURRENT_INVENTORY"))
                .singleElement()
                .extracting("sourceCount")
                .isEqualTo(1);
        assertMetric(snapshot.market().metrics(), "PACKAGING_BULK_COUNT", "1.0000");
        assertMetric(snapshot.market().metrics(), "PACKAGING_BULK_SHARE", "100.0000");
        assertMetric(snapshot.market().metrics(), "MARKET_QUALITY_MOISTURE_AVERAGE", "13.2000");
        assertThat(snapshot.market().metrics()).extracting("code")
                .doesNotContain("PROCESSING_INPUT", "AVERAGE_TRADE_PRICE");
    }

    @Test
    void keepsAnnualAndMonthlyProductionObservationsFromBeingAddedTogether() {
        productionAnnual("annual", 3, "600", "龙江县调查户", REGION);

        var annual = repository.load(
                new ObservableAnalysisScope("CORN", "230200", 2026, null, null, null),
                Set.of(REGION));
        var monthly = repository.load(
                new ObservableAnalysisScope("CORN", "230200", 2026, 8, null, null),
                Set.of(REGION));

        assertThat(annual.production().metrics()).filteredOn(metric -> metric.code().equals("EXPECTED_OUTPUT"))
                .singleElement().extracting("value").isEqualTo("60.0000");
        assertThat(monthly.production().metrics()).filteredOn(metric -> metric.code().equals("EXPECTED_OUTPUT"))
                .singleElement().extracting("value").isEqualTo("50.0000");
    }

    @Test
    void addsProductionAndEnterpriseInventoryWithoutWaitingForPendingRecords() {
        jdbc.sql("""
                INSERT INTO market.market_record_fact(
                    record_id,fact_code,value,product_code,object_type_code)
                VALUES(:id,'OPENING_INVENTORY',100,'CORN','TRADER'),
                      (:id,'ENDING_INVENTORY',80,'CORN','TRADER')
                """).param("id", PREFIX + "market-approved").update();

        var snapshot = repository.load(
                new ObservableAnalysisScope("CORN", "230200", 2026, 8, null, null),
                Set.of("230200", REGION));

        assertThat(snapshot.supply().inventory().productionEndingTonnes())
                .isEqualByComparingTo("35.0000");
        assertThat(snapshot.supply().inventory().enterpriseEndingTonnes())
                .isEqualByComparingTo("80.0000");
        assertThat(snapshot.supply().calculation().endingObservableInventoryTonnes())
                .isEqualByComparingTo("115.0000");
        assertThat(snapshot.supply().calculation().issues())
                .doesNotContain("INVENTORY_MUTUAL_EXCLUSIVITY_UNPROVEN");
        assertThat(snapshot.coverage().pendingReviewRecordCount()).isEqualTo(1);
        assertThat(snapshot.lineage()).extracting("recordId")
                .contains(PREFIX + "market-approved")
                .doesNotContain(PREFIX + "market-pending");
    }

    @Test
    void countsAnExactInventoryDuplicateOnceAndAddsDifferentWarehouses() {
        marketInventory("wa1", "APPROVED", "多库企业", "13800000000",
                "47.3500000", "123.9100000", 2026, 8, "2026-08-10", 1, "300");
        marketInventory("wa2", "APPROVED", "多库企业", "13800000000",
                "47.3500000", "123.9100000", 2026, 8, "2026-08-10", 2, "300.0000");
        marketInventory("wb", "APPROVED", "多库企业", "13800000000",
                "47.3600000", "123.9200000", 2026, 8, "2026-08-10", 1, "200");

        var snapshot = repository.load(
                new ObservableAnalysisScope("CORN", "230200", 2026, 8, null, null),
                Set.of("230200", REGION));

        assertThat(snapshot.supply().inventory().enterpriseEndingTonnes())
                .isEqualByComparingTo("500.0000");
        assertThat(snapshot.supply().inventory().reviewGroupCount()).isZero();
        assertThat(snapshot.lineage()).extracting("recordId")
                .contains(PREFIX + "market-wa2", PREFIX + "market-wb")
                .doesNotContain(PREFIX + "market-wa1");
    }

    @Test
    void isolatesOnlyAConflictingPositionAndKeepsOtherApprovedInventory() {
        marketInventory("cf1", "APPROVED", "冲突企业", "13800000001",
                "47.3700000", "123.9300000", 2026, 8, "2026-08-10", 1, "100");
        marketInventory("cf2", "APPROVED", "冲突企业", "13800000001",
                "47.3700000", "123.9300000", 2026, 8, "2026-08-10", 2, "120");
        marketInventory("valid", "APPROVED", "正常企业", "13800000002",
                "47.3800000", "123.9400000", 2026, 8, "2026-08-10", 1, "80");

        var snapshot = repository.load(
                new ObservableAnalysisScope("CORN", "230200", 2026, 8, null, null),
                Set.of("230200", REGION));

        assertThat(snapshot.supply().inventory().enterpriseEndingTonnes())
                .isEqualByComparingTo("80.0000");
        assertThat(snapshot.supply().inventory().reviewGroupCount()).isEqualTo(1);
        assertThat(snapshot.supply().calculation().endingObservableInventoryTonnes())
                .isEqualByComparingTo("115.0000");
        assertThat(snapshot.supply().calculation().qualityState())
                .isEqualTo(AnalysisQualityState.COVERAGE_REVIEW_REQUIRED);
        assertThat(snapshot.lineage()).extracting("recordId")
                .contains(PREFIX + "market-valid")
                .doesNotContain(PREFIX + "market-cf1");
        assertThat(snapshot.lineage()).filteredOn(
                        item -> item.recordId().equals(PREFIX + "market-cf2"))
                .singleElement()
                .extracting("factCodes")
                .asList()
                .doesNotContain("ENDING_INVENTORY");
    }

    @Test
    void carriesThePreviousApprovedEnterpriseEndingInventoryIntoTheCurrentOpeningLayer() {
        marketInventory("july", "APPROVED", "结转企业", "13800000003",
                "47.3900000", "123.9500000", 2026, 7, "2026-07-31", 1, "50");
        marketInventory("aug", "APPROVED", "结转企业", "13800000003",
                "47.3900000", "123.9500000", 2026, 8, "2026-08-31", 2, "80");

        var snapshot = repository.load(
                new ObservableAnalysisScope("CORN", "230200", 2026, 8, null, null),
                Set.of("230200", REGION));

        assertThat(snapshot.supply().inventory().enterpriseOpeningTonnes())
                .isEqualByComparingTo("50.0000");
        assertThat(snapshot.supply().inventory().enterpriseEndingTonnes())
                .isEqualByComparingTo("80.0000");
        assertThat(snapshot.lineage()).extracting("recordId")
                .contains(PREFIX + "market-july", PREFIX + "market-aug");
    }

    @Test
    void annualEnterpriseEndingUsesLatestApprovedInventoryPerPositionWithActualDates() {
        marketInventory("spring", "APPROVED", "年度企业甲", "13800000004",
                "47.4000000", "123.9600000", 2026, 3, "2026-03-20", 1, "300");
        marketInventory("november", "APPROVED", "年度企业甲", "13800000004",
                "47.4000000", "123.9600000", 2026, 11, "2026-11-25", 2, "320");
        marketInventory("september", "APPROVED", "年度企业乙", "13800000005",
                "47.4100000", "123.9700000", 2026, 9, "2026-09-10", 1, "200");
        marketInventory("dec-pend", "PENDING_REVIEW", "年度企业甲", "13800000004",
                "47.4000000", "123.9600000", 2026, 12, "2026-12-20", 3, "350");

        var snapshot = repository.load(
                new ObservableAnalysisScope("CORN", "230200", 2026, null, null, null),
                Set.of("230200", REGION));

        assertThat(snapshot.supply().inventory().enterpriseEndingTonnes())
                .isEqualByComparingTo("520.0000");
        assertThat(snapshot.supply().inventory().enterpriseEndingObservedFrom())
                .isEqualTo(LocalDate.parse("2026-09-10"));
        assertThat(snapshot.supply().inventory().enterpriseEndingObservedThrough())
                .isEqualTo(LocalDate.parse("2026-11-25"));
        assertThat(snapshot.methodologyVersion()).isEqualTo("OBSERVABLE_ANALYSIS_V3");
        assertThat(snapshot.coverage().pendingReviewRecordCount()).isEqualTo(2);
        assertThat(snapshot.lineage()).extracting("recordId")
                .contains(PREFIX + "market-november", PREFIX + "market-september")
                .doesNotContain(PREFIX + "market-dec-pend");
    }

    @Test
    void keepsRegionNavigationAndFactsInsideTheAuthorizedTree() {
        assertThat(repository.canNavigateRegion("230200", Set.of(REGION))).isTrue();
        assertThat(repository.canNavigateRegion(REGION, Set.of("230200"))).isTrue();
        assertThat(repository.canNavigateRegion("231100", Set.of(REGION))).isFalse();

        var snapshot = repository.load(
                new ObservableAnalysisScope("CORN", "230200", 2026, 8, null, null),
                Set.of(REGION));

        assertThat(snapshot.lineage()).extracting("regionLabel")
                .allMatch(label -> !String.valueOf(label).contains("北安"));
    }

    @Test
    void leavesTheCutoffEmptyWhenNoApprovedFactExists() {
        var snapshot = repository.load(
                new ObservableAnalysisScope("CORN", "230200", 2099, null, null, null),
                Set.of("230200", REGION));

        assertThat(snapshot.qualityState()).isEqualTo(AnalysisQualityState.NO_APPROVED_DATA);
        assertThat(snapshot.coverage().recordCount()).isZero();
        assertThat(snapshot.dataCutoffAt()).isNull();
    }

    @Test
    void loadsEveryAuthorizedRootWhenTheBusinessScopeIsOverall() {
        var snapshot = repository.load(
                new ObservableAnalysisScope(
                        "CORN", ObservableAnalysisScope.ALL_AUTHORIZED_REGIONS, 2026, 8, null, null),
                Set.of("*"));

        assertThat(snapshot.lineage()).extracting("regionLabel")
                .contains("龙江县", "爱辉区");
        assertThat(snapshot.production().metrics())
                .filteredOn(metric -> metric.code().equals("EXPECTED_OUTPUT"))
                .singleElement().satisfies(metric -> {
                    assertThat(metric.value()).isEqualTo("149.9000");
                    assertThat(metric.sourceCount()).isEqualTo(2);
                });
    }

    @Test
    void treatsRepeatedImportsOfTheSameNamedContactAndCoordinatesAsOneEffectiveSample() {
        insertMissingSamplePoint(DUPLICATE_POINT_ONE, "重复导入样本一");
        insertMissingSamplePoint(DUPLICATE_POINT_TWO, "重复导入样本二");
        productionWithPoint("duplicate-one", 1, "300", DUPLICATE_POINT_ONE);
        productionWithPoint("duplicate-two", 2, "700", DUPLICATE_POINT_TWO);

        var snapshot = repository.load(
                new ObservableAnalysisScope("CORN", "230200", 2026, 8, null, null),
                Set.of("230200", REGION));

        assertThat(snapshot.production().metrics())
                .filteredOn(metric -> metric.code().equals("EXPECTED_OUTPUT"))
                .singleElement().satisfies(metric -> {
                    assertThat(metric.value()).isEqualTo("120.0000");
                    assertThat(metric.sourceCount()).isEqualTo(2);
                });
        assertThat(snapshot.lineage()).extracting("recordId")
                .contains(PREFIX + "duplicate-two")
                .doesNotContain(PREFIX + "duplicate-one");
    }

    private void insertMissingSamplePoint(UUID id, String name) {
        jdbc.sql("""
                INSERT INTO registry.sample_point(
                    sample_point_id,kind_code,canonical_name,region_code,approval_state,location_state,
                    effective_from,created_by,updated_by)
                VALUES(:id,'SURVEY_SITE',:name,:region,'APPROVED','MISSING',
                    DATE '2026-01-01','production-tester','production-tester')
                """).param("id", id).param("name", name).param("region", REGION).update();
    }

    private static void assertMetric(
            java.util.List<com.cofco.qiqihar.graintrade.analysis.application.ObservableMetric> metrics,
            String code,
            String value) {
        assertThat(metrics).filteredOn(metric -> metric.code().equals(code))
                .singleElement().extracting("value").isEqualTo(value);
    }

    private void productionWithPoint(String suffix, long version, String yield, UUID samplePointId) {
        String id = PREFIX + suffix;
        jdbc.sql("""
                INSERT INTO production.production_record(
                    record_id,product_code,object_type_code,region_code,survey_date,reported_at,
                    cultivated_area_mu,yield_per_mu_kg,status_code,last_modified_by,version,
                    survey_year,survey_month,survey_period_precision,survey_period_governance_state,
                    sample_point_id)
                VALUES(:id,'CORN','FARMER',:region,DATE '2026-08-10',
                    TIMESTAMPTZ '2026-08-11 08:00:00+08',100,:yield,'APPROVED','analysis-test',:version,
                    2026,8,'YEAR_MONTH','CONFIRMED',:samplePointId)
                """).param("id", id).param("region", REGION).param("yield", new BigDecimal(yield))
                .param("version", version).param("samplePointId", samplePointId).update();
        jdbc.sql("""
                INSERT INTO production.production_record_submission_metadata(record_id,field_code,value)
                VALUES(:id,'PROD_SAMPLE_NAME','同一个农户'),
                      (:id,'PROD_SAMPLE_CONTACT','13800000000'),
                      (:id,'PROD_SAMPLE_LATITUDE','47.1000000'),
                      (:id,'PROD_SAMPLE_LONGITUDE','123.1000000')
                """).param("id", id).update();
    }

    private void production(
            String suffix, String status, long version, String yield, String sample, String region) {
        String id = PREFIX + suffix;
        jdbc.sql("""
                INSERT INTO production.production_record(
                    record_id,product_code,object_type_code,region_code,survey_date,reported_at,
                    cultivated_area_mu,yield_per_mu_kg,status_code,last_modified_by,version,
                    survey_year,survey_month,survey_period_precision,survey_period_governance_state)
                VALUES(:id,'CORN','FARMER',:region,DATE '2026-08-10',
                    TIMESTAMPTZ '2026-08-11 08:00:00+08',100,:yield,:status,'analysis-test',:version,
                    2026,8,'YEAR_MONTH','CONFIRMED')
                """).param("id", id).param("region", region).param("yield", new BigDecimal(yield))
                .param("status", status).param("version", version).update();
        jdbc.sql("""
                INSERT INTO production.production_record_submission_metadata(record_id,field_code,value)
                VALUES(:id,'PROD_SAMPLE_NAME',:sample),
                      (:id,'PROD_OPENING_INVENTORY','10'),
                      (:id,'PROD_SALES_VOLUME','20'),
                      (:id,'PROD_SELF_USE','5'),
                      (:id,'PROD_ENDING_INVENTORY','35')
                """).param("id", id).param("sample", sample).update();
    }

    private void market(String suffix, String status, String price, String region) {
        String contact = "approved".equals(suffix) ? "13900000001" : "13900000002";
        marketRecord(suffix, status, price, region, "市场企业" + suffix, contact,
                "47.3000000", "123.9000000", 2026, 8, "2026-08-10", 1);
    }

    private void marketInventory(
            String suffix, String status, String name, String contact,
            String latitude, String longitude, int year, int month,
            String tradeDate, long version, String endingInventory) {
        marketRecord(suffix, status, "2500", REGION, name, contact,
                latitude, longitude, year, month, tradeDate, version);
        jdbc.sql("""
                INSERT INTO market.market_record_fact(
                    record_id,fact_code,value,product_code,object_type_code)
                VALUES(:id,'ENDING_INVENTORY',:ending,'CORN','TRADER')
                """).param("id", PREFIX + "market-" + suffix)
                .param("ending", new BigDecimal(endingInventory)).update();
    }

    private void marketRecord(
            String suffix, String status, String price, String region, String name, String contact,
            String latitude, String longitude, int year, int month, String tradeDate, long version) {
        String id = PREFIX + "market-" + suffix;
        jdbc.sql("""
                INSERT INTO market.market_record(
                    record_id,product_code,object_type_code,region_code,trade_date,reported_at,
                    purchase_base_price,trade_direction,carriage_board_amount,packaging_amount,
                    freight_amount,packaging_form,status_code,last_modified_by,version,
                    survey_year,survey_month,survey_period_precision,survey_period_governance_state)
                VALUES(:id,'CORN','TRADER',:region,CAST(:tradeDate AS date),
                    CAST(:tradeDate AS date) + INTERVAL '1 day',:price,'PURCHASE',0,0,0,'BULK',
                    :status,'analysis-test',:version,:year,:month,'YEAR_MONTH','CONFIRMED')
                """).param("id", id).param("region", region).param("price", new BigDecimal(price))
                .param("status", status).param("tradeDate", tradeDate).param("version", version)
                .param("year", year).param("month", month).update();
        jdbc.sql("""
                INSERT INTO market.market_record_fact(
                    record_id,fact_code,value,product_code,object_type_code)
                VALUES(:id,'PURCHASE_VOLUME',30,'CORN','TRADER')
                """).param("id", id).update();
        jdbc.sql("""
                INSERT INTO market.market_record_core_value(
                    record_id,product_code,field_code,domain_binding,value)
                VALUES(:id,'CORN','MKT_SAMPLE_NAME','EXTENSION',:name),
                      (:id,'CORN','MKT_SAMPLE_CONTACT','EXTENSION',:contact),
                      (:id,'CORN','MKT_SAMPLE_LATITUDE','EXTENSION',:latitude),
                      (:id,'CORN','MKT_SAMPLE_LONGITUDE','EXTENSION',:longitude)
                """).param("id", id).param("name", name).param("contact", contact)
                .param("latitude", latitude).param("longitude", longitude).update();
    }

    private void productionAnnual(
            String suffix, long version, String yield, String sample, String region) {
        String id = PREFIX + suffix;
        jdbc.sql("""
                INSERT INTO production.production_record(
                    record_id,product_code,object_type_code,region_code,survey_date,reported_at,
                    cultivated_area_mu,yield_per_mu_kg,status_code,last_modified_by,version,
                    survey_year,survey_month,survey_period_precision,survey_period_governance_state)
                VALUES(:id,'CORN','FARMER',:region,DATE '2026-12-31',
                    TIMESTAMPTZ '2027-01-02 08:00:00+08',100,:yield,'APPROVED','analysis-test',:version,
                    2026,NULL,'YEAR','CONFIRMED')
                """).param("id", id).param("region", region).param("yield", new BigDecimal(yield))
                .param("version", version).update();
        jdbc.sql("""
                INSERT INTO production.production_record_submission_metadata(record_id,field_code,value)
                VALUES(:id,'PROD_SAMPLE_NAME',:sample),
                      (:id,'PROD_OPENING_INVENTORY','10'),
                      (:id,'PROD_SALES_VOLUME','20'),
                      (:id,'PROD_SELF_USE','5'),
                      (:id,'PROD_ENDING_INVENTORY','45')
                """).param("id", id).param("sample", sample).update();
    }

    private void route(String suffix, String direction, String value, String status, String region) {
        UUID id = UUID.nameUUIDFromBytes((PREFIX + suffix).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        jdbc.sql("""
                INSERT INTO logistics.route_event(
                    event_id,product_code,monitoring_period_code,collection_date,reported_at,
                    business_region_code,origin_region_code,origin_node_id,origin_node_code,
                    destination_region_code,destination_node_id,destination_node_code,
                    transport_mode_code,direction_code,source_organization,reporter,status_code,
                    version,created_by,last_modified_by,created_at,updated_at,
                    survey_year,survey_month,survey_period_precision,survey_period_governance_state)
                SELECT :id,'CORN',:period,DATE '2026-08-10',TIMESTAMPTZ '2026-08-11 10:00:00+08',
                    :region,:region,origin.node_id,origin.node_code,:region,destination.node_id,
                    destination.node_code,'RAIL',:direction,:source,'分析填报员',:status,1,
                    'analysis-test','analysis-test',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,
                    2026,8,'YEAR_MONTH','CONFIRMED'
                FROM logistics.logistics_node origin,logistics.logistics_node destination
                WHERE origin.node_code=:origin AND destination.node_code=:destination
                """).param("id", id).param("period", PERIOD).param("region", region)
                .param("direction", direction).param("source", PREFIX + suffix)
                .param("status", status).param("origin", PREFIX + "origin")
                .param("destination", PREFIX + "destination").update();
        jdbc.sql("""
                INSERT INTO logistics.route_fact(event_id,fact_code,value,unit_code)
                VALUES(:id,'ROUTE_VOLUME',:value,'吨')
                """).param("id", id).param("value", new BigDecimal(value)).update();
    }
}
