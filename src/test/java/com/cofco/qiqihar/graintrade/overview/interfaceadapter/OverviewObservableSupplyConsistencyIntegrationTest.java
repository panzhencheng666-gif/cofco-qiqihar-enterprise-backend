package com.cofco.qiqihar.graintrade.overview.interfaceadapter;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.hamcrest.Matchers.empty;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.testsupport.ProtectedTestDatabaseConfiguration;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import java.math.BigDecimal;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = GrainTradeApplication.class)
@AutoConfigureMockMvc
@UsesProtectedTestDatabase
class OverviewObservableSupplyConsistencyIntegrationTest {
    private static final String ACTOR = "production-tester";
    private static final String REGION = "230208";
    private static final String PRODUCTION_ID = "99100000-0000-0000-0000-000000000001";

    @Autowired MockMvc mvc;
    @Autowired DataSource dataSource;
    private JdbcClient jdbc;

    @BeforeEach
    void setUpCompleteApprovedObservableSupplyWithoutPublishedRun() {
        jdbc = JdbcClient.create(dataSource);
        clearBusinessFacts();
        ProtectedTestDatabaseConfiguration.provisionSecurityTestSubjects(jdbc);
        insertProduction();
        insertMarketInventory("prior", 2025, "5");
        insertMarketInventory("current", 2026, "7");
        insertRoute("INFLOW", "3");
        insertRoute("OUTFLOW", "1");
    }

    @AfterEach
    void clearCompleteApprovedObservableSupply() {
        clearBusinessFacts();
    }

    private void clearBusinessFacts() {
        jdbc.sql("""
                TRUNCATE reporting.report_audit_event,reporting.report_publication,
                  reporting.report_export_task,reporting.report_preview,reporting.approved_dataset,
                  platform.business_audit_event,platform.business_event_outbox,
                  supply.calculation_run,logistics.route_event,market.market_record,
                  production.production_record RESTART IDENTITY CASCADE
                """).update();
    }

    @Test
    void sampleOverviewDoesNotExposeTheLegacySampleDerivedSupplyBalance()
            throws Exception {
        mvc.perform(get("/api/v1/observable-analysis/snapshots").principal(() -> ACTOR)
                        .queryParam("productCode", "CORN").queryParam("regionCode", REGION)
                        .queryParam("surveyYear", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.supply.calculation.qualityState").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.supply.calculation.totalSupplyTonnes").value("18.2000"))
                .andExpect(jsonPath("$.data.supply.calculation.totalUseTonnes").value("3.2000"))
                .andExpect(jsonPath("$.data.supply.calculation.endingObservableInventoryTonnes")
                        .value("15.0000"));

        mvc.perform(get("/api/v1/overview/dashboard").principal(() -> ACTOR)
                        .queryParam("productCode", "CORN").queryParam("regionCode", REGION)
                        .queryParam("year", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.metrics[?(@.code =~ /SUPPLY_.*/)]").value(empty()))
                .andExpect(jsonPath("$.data.metrics[?(@.code == 'REGION_SURPLUS')]").value(empty()))
                .andExpect(jsonPath("$.data.businessTables[?(@.domain == 'SUPPLY')]").value(empty()));
    }

    private void insertProduction() {
        jdbc.sql("""
                INSERT INTO production.production_record(
                  record_id,product_code,object_type_code,region_code,survey_date,reported_at,
                  cultivated_area_mu,yield_per_mu_kg,status_code,last_modified_by,
                  survey_year,survey_month,survey_period_precision,survey_period_governance_state)
                VALUES(:id,'CORN','FARMER',:region,DATE '2026-12-31',
                  TIMESTAMPTZ '2026-12-31 10:00:00+08',10,20,'APPROVED',:actor,
                  2026,NULL,'YEAR','CONFIRMED')
                """).param("id", PRODUCTION_ID).param("region", REGION).param("actor", ACTOR).update();
        jdbc.sql("""
                INSERT INTO production.production_record_submission_metadata(record_id,field_code,value)
                VALUES(:id,'PROD_SAMPLE_NAME','供需联动农户'),
                      (:id,'PROD_SAMPLE_CONTACT','13800000001'),
                      (:id,'PROD_OPENING_INVENTORY','10'),
                      (:id,'PROD_SELF_USE','2'),
                      (:id,'PROD_ENDING_INVENTORY','8')
                """).param("id", PRODUCTION_ID).update();
    }

    private void insertMarketInventory(String suffix, int year, String endingInventory) {
        String id = UUID.nameUUIDFromBytes(("overview-supply-market-" + suffix).getBytes()).toString();
        jdbc.sql("""
                INSERT INTO market.market_record(
                  record_id,product_code,object_type_code,region_code,trade_date,reported_at,
                  purchase_base_price,trade_direction,carriage_board_amount,packaging_amount,
                  freight_amount,packaging_form,status_code,last_modified_by,version,
                  survey_year,survey_month,survey_period_precision,survey_period_governance_state)
                VALUES(:id,'CORN','TRADER',:region,make_date(:year,12,31),
                  make_timestamptz(:year,12,31,11,0,0,'Asia/Shanghai'),2300,'PURCHASE',0,0,0,
                  'BULK','APPROVED','market-tester',1,:year,NULL,'YEAR','CONFIRMED')
                """).param("id", id).param("region", REGION).param("year", year).update();
        jdbc.sql("""
                INSERT INTO market.market_record_core_value(
                  record_id,product_code,field_code,domain_binding,value)
                VALUES(:id,'CORN','MKT_SAMPLE_NAME','EXTENSION',:name),
                      (:id,'CORN','MKT_SAMPLE_CONTACT','EXTENSION','13800000002'),
                      (:id,'CORN','MKT_SAMPLE_LATITUDE','EXTENSION','47.3500000'),
                      (:id,'CORN','MKT_SAMPLE_LONGITUDE','EXTENSION','123.9100000')
                """).param("id", id).param("name", "供需联动企业" + suffix).update();
        jdbc.sql("""
                INSERT INTO market.market_record_fact(
                  record_id,fact_code,value,product_code,object_type_code)
                VALUES(:id,'ENDING_INVENTORY',:value,'CORN','TRADER')
                """).param("id", id).param("value", new BigDecimal(endingInventory)).update();
    }

    private void insertRoute(String direction, String volume) {
        UUID id = UUID.nameUUIDFromBytes(("overview-supply-route-" + direction).getBytes());
        jdbc.sql("""
                INSERT INTO logistics.route_event(
                  event_id,product_code,collection_date,reported_at,business_region_code,
                  origin_region_code,destination_region_code,transport_mode_code,direction_code,
                  source_organization,reporter,status_code,version,created_by,last_modified_by,
                  created_at,updated_at,survey_year,survey_month,survey_period_precision,
                  survey_period_governance_state)
                VALUES(:id,'CORN',DATE '2026-12-31',TIMESTAMPTZ '2026-12-31 12:00:00+08',
                  :region,:region,:region,'RAIL',:direction,:source,'物流填报员','APPROVED',1,
                  'logistics-tester','logistics-tester',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,
                  2026,NULL,'YEAR','CONFIRMED')
                """).param("id", id).param("region", REGION).param("direction", direction)
                .param("source", "供需联动物流" + direction).update();
        jdbc.sql("""
                INSERT INTO logistics.route_fact(event_id,fact_code,value,unit_code)
                VALUES(:id,'ROUTE_VOLUME',:volume,'吨')
                """).param("id", id).param("volume", new BigDecimal(volume)).update();
    }
}
