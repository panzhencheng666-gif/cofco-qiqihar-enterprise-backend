package com.cofco.qiqihar.graintrade.analysis.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.cofco.qiqihar.graintrade.analysis.application.ObservableAnalysisScope;
import com.cofco.qiqihar.graintrade.analysis.domain.AnalysisQualityState;
import com.cofco.qiqihar.graintrade.testsupport.ProtectedTestDatabase;
import java.math.BigDecimal;
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
    private static final ProtectedTestDatabase DATABASE = ProtectedTestDatabase.shared();

    private final JdbcClient jdbc = JdbcClient.create(DATABASE.dataSource());
    private final JdbcObservableAnalysisRepository repository =
            new JdbcObservableAnalysisRepository(DATABASE.dataSource());

    @BeforeEach
    void insertFixtures() {
        DATABASE.flyway().migrate();
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
        jdbc.sql("DELETE FROM platform.business_period WHERE code=:code")
                .param("code", PERIOD).update();
    }

    @Test
    void buildsOneSnapshotFromApprovedConfirmedFactsWithoutDoubleCountingSupersededRecords() {
        var snapshot = repository.load(
                new ObservableAnalysisScope("CORN", "230200", 2026, 8, null, null),
                Set.of("230200", REGION));

        assertThat(snapshot.qualityState()).isEqualTo(AnalysisQualityState.AVAILABLE);
        assertThat(snapshot.coverage().recordCount()).isEqualTo(4);
        assertThat(snapshot.coverage().excludedRecordCount()).isGreaterThanOrEqualTo(2);
        assertThat(snapshot.production().metrics()).filteredOn(metric -> metric.code().equals("EXPECTED_OUTPUT"))
                .singleElement().satisfies(metric -> {
                    assertThat(metric.value()).isEqualTo("50.0000");
                    assertThat(metric.sourceCount()).isOne();
                });
        assertThat(snapshot.market().metrics()).filteredOn(metric -> metric.code().equals("AVERAGE_TRADE_PRICE"))
                .singleElement().satisfies(metric -> {
                    assertThat(metric.value()).isEqualTo("2500.0000");
                    assertThat(metric.sourceCount()).isOne();
                });
        assertThat(snapshot.logistics().metrics()).filteredOn(metric -> metric.code().equals("INFLOW_VOLUME"))
                .singleElement().satisfies(metric -> assertThat(metric.value()).isEqualTo("20.0000"));
        assertThat(snapshot.supply().calculation().inferredOtherAbsorptionTonnes())
                .isEqualByComparingTo("25.0000");
        assertThat(snapshot.lineage()).extracting("recordId")
                .contains(PREFIX + "new", PREFIX + "market-approved")
                .doesNotContain(PREFIX + "old", PREFIX + "draft", PREFIX + "market-pending");
        assertThat(snapshot.analysisVersion()).startsWith("sha256:");
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
    void blocksCrossDomainInventoryAdditionWhenMutualExclusivityIsUnproven() {
        jdbc.sql("""
                INSERT INTO market.market_record_fact(
                    record_id,fact_code,value,product_code,object_type_code)
                VALUES(:id,'OPENING_INVENTORY',100,'CORN','TRADER'),
                      (:id,'ENDING_INVENTORY',80,'CORN','TRADER')
                """).param("id", PREFIX + "market-approved").update();

        var snapshot = repository.load(
                new ObservableAnalysisScope("CORN", "230200", 2026, 8, null, null),
                Set.of("230200", REGION));

        assertThat(snapshot.qualityState())
                .isEqualTo(AnalysisQualityState.COVERAGE_REVIEW_REQUIRED);
        assertThat(snapshot.supply().calculation().issues())
                .containsExactly("INVENTORY_MUTUAL_EXCLUSIVITY_UNPROVEN");
        assertThat(snapshot.supply().calculation().openingObservableInventoryTonnes()).isNull();
        assertThat(snapshot.supply().calculation().endingObservableInventoryTonnes()).isNull();
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
        String id = PREFIX + "market-" + suffix;
        jdbc.sql("""
                INSERT INTO market.market_record(
                    record_id,product_code,object_type_code,region_code,trade_date,reported_at,
                    purchase_base_price,trade_direction,carriage_board_amount,packaging_amount,
                    freight_amount,packaging_form,status_code,last_modified_by,version,
                    survey_year,survey_month,survey_period_precision,survey_period_governance_state)
                VALUES(:id,'CORN','TRADER',:region,DATE '2026-08-10',
                    TIMESTAMPTZ '2026-08-11 09:00:00+08',:price,'PURCHASE',0,0,0,'BULK',
                    :status,'analysis-test',1,2026,8,'YEAR_MONTH','CONFIRMED')
                """).param("id", id).param("region", region).param("price", new BigDecimal(price))
                .param("status", status).update();
        jdbc.sql("""
                INSERT INTO market.market_record_fact(
                    record_id,fact_code,value,product_code,object_type_code)
                VALUES(:id,'PURCHASE_VOLUME',30,'CORN','TRADER')
                """).param("id", id).update();
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
