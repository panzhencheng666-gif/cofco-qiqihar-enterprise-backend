package com.cofco.qiqihar.graintrade.overview.interfaceadapter;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = GrainTradeApplication.class,
        properties = "qiqihar.security.require-read-authentication=true")
@AutoConfigureMockMvc
@UsesProtectedTestDatabase
class AnnualComparisonRestIntegrationTest {
    private static final String READER = "annual-comparison-reader";
    private static final String REGION = "230200993";
    private static final String OTHER_REGION = "230200994";
    private static final String PERIOD = "ANNUAL-COMPARISON-2026-Q3";

    @Autowired MockMvc mvc;
    @Autowired DataSource dataSource;
    private JdbcClient jdbc;

    @BeforeEach
    void setUpPublishedRecords() {
        jdbc = JdbcClient.create(dataSource);
        clean();
        jdbc.sql("""
                INSERT INTO platform.region(code,name,parent_code,administrative_level,sort_order)
                VALUES (:region,'年度同比授权地区','230200','COUNTY',993),
                       (:other,'年度同比未授权地区','230200','COUNTY',994)
                """).param("region", REGION).param("other", OTHER_REGION).update();
        jdbc.sql("""
                INSERT INTO platform.monitoring_scope_region(scope_code,region_code,included)
                VALUES ('FORMAL_BUSINESS',:region,true),('FORMAL_BUSINESS',:other,true)
                """).param("region", REGION).param("other", OTHER_REGION).update();
        jdbc.sql("""
                INSERT INTO platform.work_unit(code,name,sort_order) VALUES ('ANNUAL_COMPARISON','年度同比测试单位',9940);
                INSERT INTO platform.work_unit_region_scope(work_unit_code,region_code) VALUES ('ANNUAL_COMPARISON',:region);
                INSERT INTO platform.security_user(subject_id,display_name,work_unit_code) VALUES (:reader,'年度同比读取员','ANNUAL_COMPARISON');
                INSERT INTO platform.security_user_role(subject_id,role_code) VALUES (:reader,'SYSTEM_ADMIN');
                INSERT INTO platform.security_user_region_scope(subject_id,region_code) VALUES (:reader,:region);
                INSERT INTO platform.business_period(code,name,starts_on,ends_on,sort_order)
                VALUES (:period,'年度同比截止期间',DATE '2026-07-01',DATE '2026-09-30',9940)
                """).param("reader", READER).param("region", REGION).param("period", PERIOD).update();
        for (int year = 2023; year <= 2026; year++) {
            int area = year - 2014;
            jdbc.sql("""
                    INSERT INTO production.production_record(record_id,product_code,object_type_code,region_code,cultivar_code,
                      survey_date,reported_at,cultivated_area_mu,yield_per_mu_kg,status_code,last_modified_by)
                    VALUES (:id,'SOYBEAN','FARMER',:region,'HEINONG_84',CAST(:date AS date),CAST(:reportedAt AS timestamptz),
                      :area,20,'APPROVED',:reader)
                    """).param("id", "annual-production-" + year).param("region", REGION)
                    .param("date", year + "-08-10").param("reportedAt", year + "-08-11T08:00:00+08:00")
                    .param("area", area).param("reader", READER).update();
            jdbc.sql("""
                    INSERT INTO production.production_record_submission_metadata(record_id,field_code,value)
                    VALUES (:id,'PROD_HARVEST_AREA_MU',:harvest),
                           (:id,'PROD_OPENING_INVENTORY',:opening)
                    """).param("id", "annual-production-" + year)
                    .param("harvest", Integer.toString(area - 1))
                    .param("opening", Integer.toString(year - 2020)).update();
            jdbc.sql("""
                    INSERT INTO production.production_record_quality(record_id,quality_code,value)
                    VALUES (:id,'MOISTURE',:moisture)
                    """).param("id", "annual-production-" + year)
                    .param("moisture", year - 2010).update();
            jdbc.sql("""
                    INSERT INTO market.market_record(record_id,product_code,object_type_code,region_code,trade_date,reported_at,
                      purchase_base_price,trade_direction,carriage_board_amount,packaging_amount,freight_amount,packaging_form,status_code,last_modified_by)
                    VALUES (:id,'SOYBEAN','TRADER',:region,CAST(:date AS date),CAST(:reportedAt AS timestamptz),
                      :price,'PURCHASE',0,0,0,'BULK','APPROVED',:reader)
                    """).param("id", "annual-market-" + year).param("region", REGION)
                    .param("date", year + "-08-10").param("reportedAt", year + "-08-11T08:00:00+08:00")
                    .param("price", year - 2000).param("reader", READER).update();
            jdbc.sql("""
                    INSERT INTO market.market_record_fact(record_id,fact_code,value,product_code,object_type_code)
                    VALUES (:id,'PURCHASE_VOLUME',:volume,'SOYBEAN','TRADER')
                    """).param("id", "annual-market-" + year)
                    .param("volume", year - 2015).update();
        }
        jdbc.sql("""
                INSERT INTO production.production_record(record_id,product_code,object_type_code,region_code,cultivar_code,
                  survey_date,reported_at,cultivated_area_mu,yield_per_mu_kg,status_code,last_modified_by)
                VALUES ('annual-production-draft','SOYBEAN','FARMER',:region,'HEINONG_84',DATE '2026-08-10','2026-08-11T08:00:00+08:00',999,999,'DRAFT',:reader),
                       ('annual-production-other-cultivar','SOYBEAN','FARMER',:region,'DONGSHENG_22',DATE '2026-08-10','2026-08-11T08:00:00+08:00',999,999,'APPROVED',:reader)
                """).param("region", REGION).param("reader", READER).update();
    }

    @Test
    void exposesDatabaseOwnedImportantIndicatorsAndAggregatesTheirApprovedFacts() throws Exception {
        mvc.perform(get("/api/v1/overview/annual-comparison-definitions").principal(() -> READER)
                        .queryParam("sourceDomain", "PRODUCTION").queryParam("productCode", "SOYBEAN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.code == 'PRODUCTION_CULTIVATED_AREA')].name")
                        .value("核定播种面积"))
                .andExpect(jsonPath("$.data[?(@.code == 'PRODUCTION_PROD_HARVEST_AREA_MU')].name")
                        .value("产情核定预计收获面积"))
                .andExpect(jsonPath("$.data[?(@.code == 'PRODUCTION_PROD_OPENING_INVENTORY')].name")
                        .value("产情核定期初库存"))
                .andExpect(jsonPath("$.data[?(@.code == 'PRODUCTION_MOISTURE')].name")
                        .value("产情核定水分"));

        mvc.perform(get("/api/v1/overview/annual-comparison-definitions").principal(() -> READER)
                        .queryParam("sourceDomain", "MARKET").queryParam("productCode", "SOYBEAN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.code == 'MARKET_AVERAGE_PURCHASE_PRICE')].name")
                        .value("核定平均采购价格"))
                .andExpect(jsonPath("$.data[?(@.code == 'MARKET_AVERAGE_SALE_PRICE')].name")
                        .value("核定平均销售价格"))
                .andExpect(jsonPath("$.data[?(@.code == 'MARKET_PURCHASE_VOLUME')].name")
                        .value("市场核定采购量"))
                .andExpect(jsonPath("$.data[?(@.code == 'MARKET_STOCK_INFLOW')]").isEmpty())
                .andExpect(jsonPath("$.data[?(@.code == 'MARKET_STORAGE_LOSS')]").isEmpty());

        mvc.perform(get("/api/v1/overview/annual-comparisons").principal(() -> READER)
                        .queryParam("productCode", "SOYBEAN").queryParam("cultivarCode", "HEINONG_84")
                        .queryParam("regionCode", REGION).queryParam("periodCode", PERIOD)
                        .queryParam("indicatorCode", "PRODUCTION_PROD_HARVEST_AREA_MU"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.points[0].value").value(11.0))
                .andExpect(jsonPath("$.data.points[3].value").value(8.0));

        mvc.perform(get("/api/v1/overview/annual-comparisons").principal(() -> READER)
                        .queryParam("productCode", "SOYBEAN").queryParam("regionCode", REGION)
                        .queryParam("periodCode", PERIOD).queryParam("indicatorCode", "MARKET_PURCHASE_VOLUME"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.points[0].value").value(11.0))
                .andExpect(jsonPath("$.data.points[3].value").value(8.0));
    }

    @AfterEach void tearDown() { clean(); }

    @Test
    void returnsFourComparablePublishedProductionYearsAndMarketYears() throws Exception {
        mvc.perform(get("/api/v1/overview/annual-comparisons").principal(() -> READER)
                        .queryParam("productCode", "SOYBEAN").queryParam("cultivarCode", "HEINONG_84")
                        .queryParam("regionCode", REGION).queryParam("periodCode", PERIOD)
                        .queryParam("indicatorCode", "PRODUCTION_CULTIVATED_AREA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unitCode").value("亩"))
                .andExpect(jsonPath("$.data.methodologyVersion").value("OVERVIEW_APPROVED_FACTS_V2"))
                .andExpect(jsonPath("$.data.points.length()").value(4))
                .andExpect(jsonPath("$.data.points[0].businessYear").value("2026"))
                .andExpect(jsonPath("$.data.points[0].value").value(12.0))
                .andExpect(jsonPath("$.data.points[0].sourcePublicationVersion").value("APPROVED_PRODUCTION_RECORD:v0"))
                .andExpect(jsonPath("$.data.points[3].businessYear").value("2023"))
                .andExpect(jsonPath("$.data.points[3].value").value(9.0))
                .andExpect(jsonPath("$.data.points[0].dataCutoff").value("2026-08-11T00:00:00Z"));

        mvc.perform(get("/api/v1/overview/annual-comparisons").principal(() -> READER)
                        .queryParam("productCode", "SOYBEAN").queryParam("cultivarCode", "HEINONG_84")
                        .queryParam("regionCode", REGION).queryParam("periodCode", PERIOD)
                        .queryParam("indicatorCode", "PRODUCTION_ESTIMATED_OUTPUT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unitCode").value("公斤"))
                .andExpect(jsonPath("$.data.points[0].value").value(240.0))
                .andExpect(jsonPath("$.data.points[3].value").value(180.0));

        mvc.perform(get("/api/v1/overview/annual-comparisons").principal(() -> READER)
                        .queryParam("productCode", "SOYBEAN").queryParam("regionCode", REGION)
                        .queryParam("periodCode", PERIOD).queryParam("indicatorCode", "MARKET_AVERAGE_TRADE_PRICE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unitCode").value("元/吨"))
                .andExpect(jsonPath("$.data.points[0].value").value(26.0))
                .andExpect(jsonPath("$.data.points[3].value").value(23.0));
    }

    @Test
    void aggregatesAllCultivarsWhenTheProductionCultivarFilterIsOmitted() throws Exception {
        mvc.perform(get("/api/v1/overview/annual-comparisons").principal(() -> READER)
                        .queryParam("productCode", "SOYBEAN").queryParam("regionCode", REGION)
                        .queryParam("periodCode", PERIOD)
                        .queryParam("indicatorCode", "PRODUCTION_CULTIVATED_AREA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.points[0].value").value(1011.0));
    }

    @Test
    void failsClosedForAnUnassignedRegion() throws Exception {
        mvc.perform(get("/api/v1/overview/annual-comparisons").principal(() -> READER)
                        .queryParam("productCode", "SOYBEAN").queryParam("regionCode", OTHER_REGION)
                        .queryParam("periodCode", PERIOD).queryParam("indicatorCode", "MARKET_AVERAGE_TRADE_PRICE"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_REGION_DENIED"));
    }

    @Test
    void identifiesAComparisonYearWithoutApprovedRecords() throws Exception {
        jdbc.sql("DELETE FROM market.market_record WHERE record_id='annual-market-2023'").update();

        mvc.perform(get("/api/v1/overview/annual-comparisons").principal(() -> READER)
                        .queryParam("productCode", "SOYBEAN").queryParam("regionCode", REGION)
                        .queryParam("periodCode", PERIOD).queryParam("indicatorCode", "MARKET_AVERAGE_TRADE_PRICE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.points[3].value").doesNotExist())
                .andExpect(jsonPath("$.data.points[3].sourcePublicationVersion").doesNotExist())
                .andExpect(jsonPath("$.data.points[3].dataCutoff").doesNotExist())
                .andExpect(jsonPath("$.data.points[3].missingReason").value("NO_APPROVED_RECORDS"));
    }

    private void clean() {
        if (jdbc == null) return;
        jdbc.sql("DELETE FROM market.market_record WHERE record_id LIKE 'annual-market-%'").update();
        jdbc.sql("DELETE FROM production.production_record WHERE record_id LIKE 'annual-production-%'").update();
        jdbc.sql("DELETE FROM platform.business_period WHERE code=:period").param("period", PERIOD).update();
        jdbc.sql("DELETE FROM platform.security_user_region_scope WHERE subject_id=:reader").param("reader", READER).update();
        jdbc.sql("DELETE FROM platform.security_user_role WHERE subject_id=:reader").param("reader", READER).update();
        jdbc.sql("DELETE FROM platform.security_user WHERE subject_id=:reader").param("reader", READER).update();
        jdbc.sql("DELETE FROM platform.work_unit_region_scope WHERE work_unit_code='ANNUAL_COMPARISON'").update();
        jdbc.sql("DELETE FROM platform.work_unit WHERE code='ANNUAL_COMPARISON'").update();
        jdbc.sql("DELETE FROM platform.monitoring_scope_region WHERE region_code IN (:codes)")
                .param("codes", List.of(REGION, OTHER_REGION)).update();
        jdbc.sql("DELETE FROM platform.region WHERE code IN (:codes)").param("codes", List.of(REGION, OTHER_REGION)).update();
    }
}
