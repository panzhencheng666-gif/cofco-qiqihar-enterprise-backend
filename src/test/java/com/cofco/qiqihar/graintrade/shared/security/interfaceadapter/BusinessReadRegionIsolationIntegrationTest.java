package com.cofco.qiqihar.graintrade.shared.security.interfaceadapter;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.testsupport.GovernedMasterDataFixtures;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import com.cofco.qiqihar.graintrade.workflow.application.WorkItemProjection;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
        classes = GrainTradeApplication.class,
        properties = "qiqihar.security.require-read-authentication=true")
@AutoConfigureMockMvc
@UsesProtectedTestDatabase
class BusinessReadRegionIsolationIntegrationTest {
    private static final String READER_A = "region-reader-a";
    private static final String READER_B = "region-reader-b";
    private static final String REGION_A = "230200991";
    private static final String REGION_B = "230200992";
    private static final java.util.List<String> ISOLATION_REGION_CODES =
            java.util.List.of(REGION_A, REGION_B);
    private static final String PERIOD = "REGION-ISOLATION-2026";
    private static final String PRODUCTION_A = "isolation-production-a";
    private static final String PRODUCTION_B = "isolation-production-b";
    private static final String MARKET_A = "isolation-market-a";
    private static final String MARKET_B = "isolation-market-b";
    private static final String QUALITY_A = "isolation-quality-a";
    private static final String QUALITY_B = "isolation-quality-b";
    private static final String QUALITY_ORPHAN = "isolation-quality-orphan";
    private static final String QUALITY_PRODUCT_MISMATCH = "isolation-quality-product-mismatch";
    private static final String LOGISTICS_A = "10000000-0000-0000-0000-000000000001";
    private static final String LOGISTICS_B = "10000000-0000-0000-0000-000000000002";
    private static final String LOGISTICS_CROSS = "10000000-0000-0000-0000-000000000003";
    private static final String EMPTY_READER = "region-reader-empty";

    @Autowired MockMvc mockMvc;
    @Autowired DataSource dataSource;
    @MockitoBean WorkItemProjection workItemProjection;
    private JdbcClient jdbc;

    @BeforeEach
    void createDisjointSubjectsAndBusinessRows() {
        jdbc = JdbcClient.create(dataSource);
        cleanup();
        createOverviewScopeFixture();
        jdbc.sql("""
                INSERT INTO platform.work_unit(code,name,sort_order)
                VALUES ('REGION_TEST_A','区域隔离测试单位A',9910),
                       ('REGION_TEST_B','区域隔离测试单位B',9920),
                       ('REGION_TEST_EMPTY','区域隔离测试空权限单位',9930)
                """).update();
        jdbc.sql("""
                INSERT INTO platform.work_unit_region_scope(work_unit_code,region_code)
                VALUES ('REGION_TEST_A',:regionA),('REGION_TEST_B',:regionB)
                """).param("regionA", REGION_A).param("regionB", REGION_B).update();
        jdbc.sql("""
                INSERT INTO platform.security_user(subject_id,display_name,work_unit_code)
                VALUES (:readerA,'区域读取员A','REGION_TEST_A'),
                       (:readerB,'区域读取员B','REGION_TEST_B'),
                       (:emptyReader,'空区域读取员','REGION_TEST_EMPTY')
                """).param("readerA", READER_A).param("readerB", READER_B)
                .param("emptyReader", EMPTY_READER).update();
        jdbc.sql("""
                INSERT INTO platform.security_user_role(subject_id,role_code)
                VALUES (:readerA,'SYSTEM_ADMIN'),(:readerB,'SYSTEM_ADMIN'),(:emptyReader,'SYSTEM_ADMIN')
                """).param("readerA", READER_A).param("readerB", READER_B)
                .param("emptyReader", EMPTY_READER).update();
        jdbc.sql("""
                INSERT INTO platform.security_user_region_scope(subject_id,region_code)
                VALUES (:readerA,:regionA),(:readerB,:regionB)
                """).param("readerA", READER_A).param("readerB", READER_B)
                .param("regionA", REGION_A).param("regionB", REGION_B).update();
        jdbc.sql("""
                INSERT INTO platform.business_period(code,name,starts_on,ends_on,sort_order,marketing_year_code)
                VALUES (:period,'区域隔离测试期间','2026-08-01','2026-08-31',9930,'2026/27')
                """).param("period", PERIOD).update();

        production(PRODUCTION_A, REGION_A, "2026-08-04", "10");
        production(PRODUCTION_B, REGION_B, "2026-08-05", "20");
        market(MARKET_A, REGION_A, "2026-08-04", "100");
        market(MARKET_B, REGION_B, "2026-08-05", "200");
        quality(QUALITY_A, REGION_A, "区域A质量记录");
        quality(QUALITY_B, REGION_B, "区域B质量记录");
        market(QUALITY_PRODUCT_MISMATCH, REGION_A, "2026-08-06", "300");
        jdbc.sql("""
                INSERT INTO market.market_record_projection(
                  record_id,product_code,business_domain,page_kind,observed_at,values)
                VALUES (:id,'RICE','MARKET','QUALITY','2026-08-08T08:00:00+08:00',
                  '{"subjectName":"跨产品借用来源区域"}'::jsonb)
                """).param("id", QUALITY_PRODUCT_MISMATCH).update();
        jdbc.sql("""
                INSERT INTO market.market_record_projection(
                  record_id,product_code,business_domain,page_kind,observed_at,values)
                VALUES (:id,'RICE','MARKET','QUALITY','2026-08-08T08:00:00+08:00',
                  '{"subjectName":"无可信来源投影"}'::jsonb)
                """).param("id", QUALITY_ORPHAN).update();
        logistics(LOGISTICS_A, REGION_A, "ISO_A");
        logistics(LOGISTICS_B, REGION_B, "ISO_B");
        crossRegionLogistics();
        jdbc.sql("UPDATE logistics.logistics_node SET region_code=:regionA WHERE node_code LIKE 'ISO_B_%'")
                .param("regionA", REGION_A).update();
        workItem(REGION_A, "区域A待办", READER_A);
        workItem(REGION_B, "区域B待办", READER_B);
    }

    @AfterEach
    void removeIsolationFixture() {
        cleanup();
    }

    @Test
    void subjectCanReadOnlyItsAssignedRegionAcrossBusinessReads() throws Exception {
        assertForbidden("/api/v1/production-records/" + PRODUCTION_B);
        mockMvc.perform(get("/api/v1/production-records")
                        .principal(() -> READER_A)
                        .queryParam("productCode", "CORN")
                        .queryParam("pageKind", "MONITORING")
                        .queryParam("pageNumber", "0")
                        .queryParam("pageSize", "20"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(PRODUCTION_A)))
                .andExpect(content().string(not(containsString(PRODUCTION_B))));

        assertForbidden("/api/v1/market-records/" + MARKET_B);
        mockMvc.perform(get("/api/v1/market-records")
                        .principal(() -> READER_A)
                        .queryParam("productCode", "CORN")
                        .queryParam("pageKind", "MONITORING")
                        .queryParam("pageNumber", "0")
                        .queryParam("pageSize", "20"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(MARKET_A)))
                .andExpect(content().string(not(containsString(MARKET_B))));

        mockMvc.perform(get("/api/v1/market-records")
                        .principal(() -> READER_A)
                        .queryParam("productCode", "RICE")
                        .queryParam("pageKind", "QUALITY")
                        .queryParam("pageNumber", "0")
                        .queryParam("pageSize", "20"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(QUALITY_A)))
                .andExpect(content().string(not(containsString(QUALITY_B))))
                .andExpect(content().string(not(containsString(QUALITY_ORPHAN))));
        mockMvc.perform(get("/api/v1/market-records")
                        .principal(() -> READER_B)
                        .queryParam("productCode", "RICE")
                        .queryParam("pageKind", "QUALITY")
                        .queryParam("pageNumber", "0")
                        .queryParam("pageSize", "20"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(QUALITY_B)))
                .andExpect(content().string(not(containsString(QUALITY_A))))
                .andExpect(content().string(not(containsString(QUALITY_ORPHAN))));

        assertForbidden("/api/v1/logistics-records/" + LOGISTICS_B);
        mockMvc.perform(get("/api/v1/logistics-records")
                        .principal(() -> READER_A)
                        .queryParam("productCode", "CORN")
                        .queryParam("pageNumber", "0")
                        .queryParam("pageSize", "20"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(LOGISTICS_A)))
                .andExpect(content().string(not(containsString(LOGISTICS_B))))
                .andExpect(content().string(not(containsString(LOGISTICS_CROSS))));

        assertForbidden("/api/v1/logistics-records/" + LOGISTICS_A, READER_B);
        mockMvc.perform(get("/api/v1/logistics-records")
                        .principal(() -> READER_B)
                        .queryParam("productCode", "CORN")
                        .queryParam("pageNumber", "0")
                        .queryParam("pageSize", "20"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(LOGISTICS_B)))
                .andExpect(content().string(not(containsString(LOGISTICS_A))))
                .andExpect(content().string(not(containsString(LOGISTICS_CROSS))));

        mockMvc.perform(get("/api/v1/supply-accounts")
                        .principal(() -> READER_A)
                        .queryParam("productCode", "CORN")
                        .queryParam("regionCode", REGION_B)
                        .queryParam("marketingYear", "2026"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_REGION_DENIED"));

        mockMvc.perform(get("/api/v1/work-items")
                        .principal(() -> READER_A)
                        .queryParam("scope", "PENDING")
                        .queryParam("page", "0")
                        .queryParam("pageSize", "20"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("区域A待办")))
                .andExpect(content().string(not(containsString("区域B待办"))));

        mockMvc.perform(get("/api/v1/overview/indicators")
                        .principal(() -> READER_A)
                        .queryParam("productCode", "CORN")
                        .queryParam("regionCode", REGION_B)
                        .queryParam("periodCode", PERIOD)
                        .queryParam("marketingYear", "2026"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_REGION_DENIED"));
        mockMvc.perform(get("/api/v1/overview/indicators")
                        .principal(() -> READER_B)
                        .queryParam("productCode", "CORN")
                        .queryParam("regionCode", REGION_A)
                        .queryParam("periodCode", PERIOD)
                        .queryParam("marketingYear", "2026"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_REGION_DENIED"));

        mockMvc.perform(get("/api/v1/overview/regions")
                        .principal(() -> READER_A)
                        .queryParam("parentCode", "230200")
                        .queryParam("productCode", "CORN")
                        .queryParam("periodCode", PERIOD))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(REGION_A)))
                .andExpect(content().string(not(containsString(REGION_B))))
                .andExpect(jsonPath("$.data.length()").value(1));

        mockMvc.perform(get("/api/v1/overview/indicators")
                        .principal(() -> READER_A)
                        .queryParam("productCode", "CORN")
                        .queryParam("regionCode", REGION_A)
                        .queryParam("periodCode", PERIOD)
                        .queryParam("marketingYear", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.code == 'LOGISTICS_OUTFLOW_VOLUME')].value")
                        .value(org.hamcrest.Matchers.hasItem("999")))
                .andExpect(jsonPath("$.data[?(@.code == 'LOGISTICS_OUTFLOW_VOLUME')].sourceCount").value(1));
        mockMvc.perform(get("/api/v1/overview/indicators")
                        .principal(() -> READER_B)
                        .queryParam("productCode", "CORN")
                        .queryParam("regionCode", REGION_B)
                        .queryParam("periodCode", PERIOD)
                        .queryParam("marketingYear", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.code == 'LOGISTICS_INFLOW_VOLUME')].value")
                        .value(org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.nullValue())))
                .andExpect(jsonPath("$.data[?(@.code == 'LOGISTICS_INFLOW_VOLUME')].sourceCount").value(0))
                .andExpect(jsonPath("$.data[?(@.code == 'LOGISTICS_OUTFLOW_VOLUME')].value")
                        .value(org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.nullValue())))
                .andExpect(jsonPath("$.data[?(@.code == 'LOGISTICS_OUTFLOW_VOLUME')].sourceCount").value(0));

        mockMvc.perform(get("/api/v1/production-records")
                        .principal(() -> EMPTY_READER)
                        .queryParam("productCode", "CORN")
                        .queryParam("pageKind", "MONITORING")
                        .queryParam("pageNumber", "0")
                        .queryParam("pageSize", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0));
        mockMvc.perform(get("/api/v1/overview/regions")
                        .principal(() -> EMPTY_READER)
                        .queryParam("productCode", "CORN")
                        .queryParam("periodCode", PERIOD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void qualityProjectionCannotBorrowRegionAuthorizationFromDifferentProductSource() throws Exception {
        mockMvc.perform(get("/api/v1/market-records")
                        .principal(() -> READER_A)
                        .queryParam("productCode", "RICE")
                        .queryParam("pageKind", "QUALITY")
                        .queryParam("pageNumber", "0")
                        .queryParam("pageSize", "20"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(QUALITY_A)))
                .andExpect(content().string(not(containsString(QUALITY_PRODUCT_MISMATCH))))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    void navigableAncestorAggregatesOnlyAuthorizedDescendantsAndRejectsUnrelatedRegions() throws Exception {
        mockMvc.perform(get("/api/v1/overview/indicators")
                        .principal(() -> READER_A)
                        .queryParam("productCode", "CORN")
                        .queryParam("regionCode", "230200")
                        .queryParam("periodCode", PERIOD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.code == 'PRODUCTION_CULTIVATED_AREA')].value")
                        .value(org.hamcrest.Matchers.hasItem("10")))
                .andExpect(jsonPath("$.data[?(@.code == 'PRODUCTION_CULTIVATED_AREA')].sourceCount")
                        .value(org.hamcrest.Matchers.hasItem(1)));

        mockMvc.perform(get("/api/v1/overview/dashboard")
                        .principal(() -> READER_A)
                        .queryParam("productCode", "CORN")
                        .queryParam("regionCode", "230200")
                        .queryParam("periodCode", PERIOD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.metrics[?(@.code == 'PRODUCTION_CULTIVATED_AREA')].value")
                        .value(org.hamcrest.Matchers.hasItem("10")))
                .andExpect(jsonPath("$.data.metrics[?(@.code == 'PRODUCTION_CULTIVATED_AREA')].sourceCount")
                        .value(org.hamcrest.Matchers.hasItem(1)))
                .andExpect(content().string(not(containsString(REGION_B))))
                .andExpect(content().string(not(containsString("区域隔离测试地区B"))));

        mockMvc.perform(get("/api/v1/overview/indicators")
                        .principal(() -> READER_A)
                        .queryParam("productCode", "CORN")
                        .queryParam("regionCode", REGION_B)
                        .queryParam("periodCode", PERIOD))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_REGION_DENIED"));
    }

    private void assertForbidden(String path) throws Exception {
        assertForbidden(path, READER_A);
    }

    private void createOverviewScopeFixture() {
        GovernedMasterDataFixtures.insertRegion(
                jdbc, REGION_A, "区域隔离测试地区A", "230200", "COUNTY", 991);
        GovernedMasterDataFixtures.insertRegion(
                jdbc, REGION_B, "区域隔离测试地区B", "230200", "COUNTY", 992);
        jdbc.sql("""
                INSERT INTO platform.monitoring_scope_region(scope_code,region_code,included)
                VALUES ('FORMAL_BUSINESS',:regionA,true),('FORMAL_BUSINESS',:regionB,true)
                """).param("regionA", REGION_A).param("regionB", REGION_B).update();
        jdbc.sql("""
                INSERT INTO overview.administrative_boundary(
                  region_code,geometry,source_name,source_url,source_revision,source_license,geometry_sha256
                )
                SELECT fixture.region_code,
                       ST_Multi(ST_Buffer(ST_SetSRID(ST_MakePoint(123 + fixture.longitude_delta,47),4326),0.01)),
                       'region isolation fixture','https://example.invalid/region-isolation','test','test',repeat('9',64)
                  FROM (VALUES (:regionA,0.01),(:regionB,0.02)) fixture(region_code,longitude_delta)
                """).param("regionA", REGION_A).param("regionB", REGION_B).update();
        jdbc.sql("SELECT overview.refresh_administrative_boundary_render()")
                .query(Object.class).single();
    }

    private void assertForbidden(String path, String reader) throws Exception {
        mockMvc.perform(get(path).principal(() -> reader))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_REGION_DENIED"));
    }

    private void production(String id, String region, String surveyDate, String area) {
        jdbc.sql("""
                INSERT INTO production.production_record(
                  record_id,product_code,object_type_code,region_code,survey_date,reported_at,
                  cultivated_area_mu,yield_per_mu_kg,status_code,last_modified_by)
                VALUES (:id,'CORN','FARMER',:region,CAST(:surveyDate AS date),'2026-08-08T08:00:00+08:00',
                  CAST(:area AS numeric),10,'APPROVED',:reader)
                """).param("id", id).param("region", region).param("surveyDate", surveyDate)
                .param("area", area).param("reader", READER_A).update();
    }

    private void market(String id, String region, String tradeDate, String price) {
        jdbc.sql("""
                INSERT INTO market.market_record(
                  record_id,product_code,object_type_code,region_code,trade_date,reported_at,
                  purchase_base_price,trade_direction,carriage_board_amount,packaging_amount,
                  freight_amount,packaging_form,status_code,last_modified_by)
                VALUES (:id,'CORN','TRADER',:region,CAST(:tradeDate AS date),'2026-08-08T08:00:00+08:00',
                  CAST(:price AS numeric),'PURCHASE',0,0,0,'BULK','APPROVED',:reader)
                """).param("id", id).param("region", region).param("tradeDate", tradeDate)
                .param("price", price).param("reader", READER_A).update();
    }

    private void quality(String id, String region, String label) {
        jdbc.sql("""
                INSERT INTO market.market_record(
                  record_id,product_code,object_type_code,region_code,trade_date,reported_at,
                  purchase_base_price,trade_direction,carriage_board_amount,packaging_amount,
                  freight_amount,packaging_form,status_code,last_modified_by)
                VALUES (:id,'RICE','TRADER',:region,'2026-08-04','2026-08-08T08:00:00+08:00',
                  100,'PURCHASE',0,0,0,'BULK','APPROVED',:reader)
                """).param("id", id).param("region", region).param("reader", READER_A).update();
        jdbc.sql("""
                INSERT INTO market.market_record_projection(
                  record_id,product_code,business_domain,page_kind,observed_at,values)
                VALUES (:id,'RICE','MARKET','QUALITY','2026-08-08T08:00:00+08:00',
                  jsonb_build_object('subjectName',CAST(:label AS varchar)))
                """).param("id", id).param("label", label).update();
    }

    private void logistics(String id, String region, String prefix) {
        jdbc.sql("""
                INSERT INTO logistics.logistics_node(node_code,node_name,node_type_code,region_code)
                VALUES (:origin,:originName,'RAIL_NODE',:region),(:destination,:destinationName,'ROAD_NODE',:region)
                """).param("origin", prefix + "_ORIGIN").param("originName", prefix + "起点")
                .param("destination", prefix + "_DESTINATION").param("destinationName", prefix + "终点")
                .param("region", region).update();
        jdbc.sql("""
                INSERT INTO logistics.route_event(
                  event_id,product_code,monitoring_period_code,collection_date,reported_at,
                  origin_region_code,origin_node_code,destination_region_code,destination_node_code,
                  transport_mode_code,direction_code,source_organization,reporter,status_code,
                  version,created_by,last_modified_by,created_at,updated_at)
                VALUES (CAST(:id AS uuid),'CORN',:period,'2026-08-04','2026-08-08T08:00:00+08:00',
                  :region,:origin,:region,:destination,'ROAD','INFLOW','隔离测试来源','隔离测试员','APPROVED',
                  0,:reader,:reader,'2026-08-08T08:00:00+08:00','2026-08-08T08:00:00+08:00')
                """).param("id", id).param("region", region).param("origin", prefix + "_ORIGIN")
                .param("destination", prefix + "_DESTINATION").param("period", PERIOD)
                .param("reader", READER_A).update();
    }

    private void crossRegionLogistics() {
        jdbc.sql("""
                INSERT INTO logistics.logistics_node(node_code,node_name,node_type_code,region_code)
                VALUES ('ISO_CROSS_ORIGIN','跨区起点','RAIL_NODE',:regionA),
                       ('ISO_CROSS_DESTINATION','跨区终点','ROAD_NODE',:regionB)
                """).param("regionA", REGION_A).param("regionB", REGION_B).update();
        jdbc.sql("""
                INSERT INTO logistics.route_event(
                  event_id,product_code,monitoring_period_code,collection_date,reported_at,
                  origin_region_code,origin_node_code,destination_region_code,destination_node_code,
                  transport_mode_code,direction_code,source_organization,reporter,status_code,
                  version,created_by,last_modified_by,created_at,updated_at)
                VALUES (CAST(:id AS uuid),'CORN',:period,'2026-08-04','2026-08-08T08:00:00+08:00',
                  :regionA,'ISO_CROSS_ORIGIN',:regionB,'ISO_CROSS_DESTINATION','ROAD','OUTFLOW',
                  '跨区隔离测试来源','隔离测试员','APPROVED',0,:reader,:reader,
                  '2026-08-08T08:00:00+08:00','2026-08-08T08:00:00+08:00')
                """).param("id", LOGISTICS_CROSS).param("period", PERIOD)
                .param("regionA", REGION_A).param("regionB", REGION_B).param("reader", READER_A).update();
        jdbc.sql("""
                INSERT INTO logistics.route_fact(event_id,fact_code,value,unit_code)
                VALUES (CAST(:id AS uuid),'ROUTE_VOLUME',999,'吨')
                """).param("id", LOGISTICS_CROSS).update();
    }

    private void workItem(String region, String task, String responsibleSubject) {
        jdbc.sql("""
                INSERT INTO workflow.workflow_node(code,label) VALUES ('REGION_ISOLATION','区域隔离节点')
                ON CONFLICT(code) DO NOTHING
                """).update();
        jdbc.sql("""
                INSERT INTO workflow.responsible_party(party_type,external_code,display_name)
                VALUES ('USER',:subject,'区域隔离责任人') ON CONFLICT DO NOTHING
                """).param("subject", responsibleSubject).update();
        jdbc.sql("""
                INSERT INTO workflow.work_item(
                  task_name,business_domain,region_code,product_code,business_period_code,due_at,
                  workflow_node_id,status_code,responsible_party_id)
                SELECT :task,'PRODUCTION',:region,'CORN',:period,'2026-08-09T08:00:00+08:00',
                  node.node_id,'TO_REVIEW',party.responsible_party_id
                FROM workflow.workflow_node node,workflow.responsible_party party
                WHERE node.code='REGION_ISOLATION' AND party.party_type='USER'
                  AND party.external_code=:subject
                """).param("task", task).param("region", region).param("period", PERIOD)
                .param("subject", responsibleSubject).update();
    }

    private void cleanup() {
        if (jdbc == null) return;
        jdbc.sql("""
                DELETE FROM workflow.work_item
                WHERE (source_type='PRODUCTION' AND source_id IN (:productionIds))
                   OR (source_type='MARKET' AND source_id IN (:marketIds))
                   OR (source_type='LOGISTICS' AND source_id IN (:logisticsIds))
                """)
                .param("productionIds", java.util.List.of(PRODUCTION_A, PRODUCTION_B))
                .param("marketIds", java.util.List.of(
                        MARKET_A, MARKET_B, QUALITY_A, QUALITY_B,
                        QUALITY_ORPHAN, QUALITY_PRODUCT_MISMATCH))
                .param("logisticsIds", java.util.List.of(LOGISTICS_A, LOGISTICS_B, LOGISTICS_CROSS))
                .update();
        jdbc.sql("DELETE FROM workflow.work_item WHERE task_name IN ('区域A待办','区域B待办')").update();
        jdbc.sql("DELETE FROM workflow.responsible_party WHERE party_type='USER' AND external_code IN (:subjects)")
                .param("subjects", java.util.List.of(READER_A, READER_B)).update();
        jdbc.sql("DELETE FROM market.market_record_projection WHERE record_id IN (:ids)")
                .param("ids", java.util.List.of(QUALITY_A, QUALITY_B, QUALITY_ORPHAN, QUALITY_PRODUCT_MISMATCH)).update();
        jdbc.sql("DELETE FROM logistics.route_event WHERE event_id::text IN (:ids)")
                .param("ids", java.util.List.of(LOGISTICS_A, LOGISTICS_B, LOGISTICS_CROSS)).update();
        jdbc.sql("""
                DELETE FROM logistics.logistics_node
                WHERE node_code LIKE 'ISO_A_%' OR node_code LIKE 'ISO_B_%' OR node_code LIKE 'ISO_CROSS_%'
                """).update();
        jdbc.sql("DELETE FROM market.market_record WHERE record_id IN (:ids)")
                .param("ids", java.util.List.of(
                        MARKET_A, MARKET_B, QUALITY_A, QUALITY_B, QUALITY_PRODUCT_MISMATCH)).update();
        jdbc.sql("DELETE FROM production.production_record WHERE record_id IN (:ids)")
                .param("ids", java.util.List.of(PRODUCTION_A, PRODUCTION_B)).update();
        jdbc.sql("DELETE FROM platform.business_period WHERE code=:period").param("period", PERIOD).update();
        jdbc.sql("DELETE FROM platform.security_user_region_scope WHERE subject_id IN (:ids)")
                .param("ids", java.util.List.of(READER_A, READER_B, EMPTY_READER)).update();
        jdbc.sql("DELETE FROM platform.security_user_role WHERE subject_id IN (:ids)")
                .param("ids", java.util.List.of(READER_A, READER_B, EMPTY_READER)).update();
        jdbc.sql("DELETE FROM platform.security_user WHERE subject_id IN (:ids)")
                .param("ids", java.util.List.of(READER_A, READER_B, EMPTY_READER)).update();
        jdbc.sql("""
                DELETE FROM platform.work_unit_region_scope
                WHERE work_unit_code IN ('REGION_TEST_A','REGION_TEST_B','REGION_TEST_EMPTY')
                """).update();
        jdbc.sql("""
                DELETE FROM platform.work_unit
                WHERE code IN ('REGION_TEST_A','REGION_TEST_B','REGION_TEST_EMPTY')
                """).update();
        jdbc.sql("DELETE FROM overview.administrative_boundary_display_reference WHERE region_code IN (:codes)")
                .param("codes", ISOLATION_REGION_CODES).update();
        jdbc.sql("DELETE FROM overview.administrative_boundary_render WHERE region_code IN (:codes)")
                .param("codes", ISOLATION_REGION_CODES).update();
        jdbc.sql("DELETE FROM overview.administrative_boundary WHERE region_code IN (:codes)")
                .param("codes", ISOLATION_REGION_CODES).update();
        jdbc.sql("DELETE FROM platform.monitoring_scope_region WHERE region_code IN (:codes)")
                .param("codes", ISOLATION_REGION_CODES).update();
        jdbc.sql("DELETE FROM platform.work_unit_region_scope WHERE region_code IN (:codes)")
                .param("codes", ISOLATION_REGION_CODES).update();
        jdbc.sql("DELETE FROM platform.security_user_region_scope WHERE region_code IN (:codes)")
                .param("codes", ISOLATION_REGION_CODES).update();
        GovernedMasterDataFixtures.deleteRegions(jdbc, ISOLATION_REGION_CODES);
    }
}
