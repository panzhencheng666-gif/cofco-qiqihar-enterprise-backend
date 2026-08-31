package com.cofco.qiqihar.graintrade.overview.interfaceadapter;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.testsupport.GovernedMasterDataFixtures;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = GrainTradeApplication.class)
@AutoConfigureMockMvc
@UsesProtectedTestDatabase
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OverviewRestIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired DataSource dataSource;
    JdbcClient jdbc;

    @BeforeEach
    void clean() {
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("""
                DO $$ BEGIN
                  DELETE FROM overview.region_surplus_calculation_activation_audit;
                  UPDATE overview.region_surplus_calculation_contract
                  SET status_code='PENDING',effective_from=NULL,effective_to=NULL,
                    activated_by=NULL,activation_basis=NULL,activated_at=NULL
                  WHERE version_code='REGION_SURPLUS_V2';
                  UPDATE overview.region_surplus_calculation_contract
                  SET status_code='ACTIVE',effective_from=TIMESTAMPTZ '1900-01-01 00:00:00+08',
                    effective_to=NULL
                  WHERE version_code='REGION_SURPLUS_V1';
                END $$
                """).update();
        jdbc.sql("""
                TRUNCATE registry.sample_subject_resolution_audit,
                  registry.sample_subject_resolution_revision,registry.sample_subject_resolution_item,
                  registry.sample_subject_resolution_batch,production.production_record,market.market_record,
                  logistics.route_event,logistics.logistics_node,supply.calculation_run RESTART IDENTITY CASCADE
                """).update();
        jdbc.sql("""
                DELETE FROM registry.sample_point
                WHERE region_code IN ('230281999001','230281999','230281998001','230281998002',
                  '230281998','230202998001','230202998')
                """).update();
        jdbc.sql("DELETE FROM overview.administrative_boundary_display_reference WHERE region_code='230208'").update();
        jdbc.sql("DELETE FROM platform.monitoring_scope_region WHERE region_code IN ('230281999001','230281999','230281998001','230281998002','230281998','230202998001','230202998')").update();
        jdbc.sql("DELETE FROM platform.work_unit_region_scope WHERE region_code IN ('230281999001','230281999','230281998001','230281998002','230281998','230202998001','230202998')").update();
        jdbc.sql("DELETE FROM platform.security_user_region_scope WHERE region_code IN ('230281999001','230281999','230281998001','230281998002','230281998','230202998001','230202998')").update();
        jdbc.sql("DELETE FROM overview.administrative_boundary_display_reference WHERE region_code IN ('230281999001','230281999','230281998001','230281998002','230281998','230202998001','230202998')").update();
        jdbc.sql("DELETE FROM overview.administrative_boundary_render WHERE region_code IN ('230281999001','230281999','230281998001','230281998002','230281998','230202998001','230202998')").update();
        jdbc.sql("DELETE FROM overview.administrative_boundary WHERE region_code IN ('230281999001','230281999','230281998001','230281998002','230281998','230202998001','230202998')").update();
        GovernedMasterDataFixtures.deleteRegions(jdbc, java.util.List.of(
                "230281999001", "230281999", "230281998001", "230281998002",
                "230281998", "230202998001", "230202998"));
        jdbc.sql("DELETE FROM evidence.evidence_photo WHERE original_filename='overview-market-inventory.png'")
                .update();
        jdbc.sql("""
                DELETE FROM market.sample_point_inventory_contract
                WHERE sample_point_id IN (SELECT sample_point_id FROM registry.sample_point
                  WHERE canonical_name='地区余粮市场样本点' AND created_by='market-tester')
                """).update();
        jdbc.sql("""
                DELETE FROM registry.sample_point
                WHERE canonical_name='地区余粮市场样本点' AND created_by='market-tester'
                """).update();
        jdbc.sql("""
                DELETE FROM market.business_party
                WHERE current_name='地区余粮市场样本点' AND created_by='market-tester'
                """).update();
        jdbc.sql("""
                DELETE FROM registry.sample_point
                WHERE canonical_name='余粮主体样本点' AND created_by='production-tester'
                """).update();
        jdbc.sql("""
                INSERT INTO platform.business_period(code,name,starts_on,ends_on,sort_order,marketing_year_code)
                VALUES('2026-Q3','2026年第三季度',DATE '2026-07-01',DATE '2026-09-30',202603,'2026/27')
                ON CONFLICT(code) DO NOTHING
                """).update();
        jdbc.sql("SELECT overview.refresh_administrative_boundary_render()")
                .query(Object.class).single();
    }
    @AfterEach void cleanAfterEach() { clean(); }

    @Test
    void exposesOnlyAuditedSurveyYearsAndKeepsAnnualDashboardValuesIsolated() throws Exception {
        jdbc.sql("""
                INSERT INTO production.production_record(record_id,product_code,object_type_code,region_code,survey_date,
                  reported_at,cultivated_area_mu,yield_per_mu_kg,status_code,last_modified_by)
                VALUES(:corn2025,'CORN','FARMER','230208',DATE '2025-08-01',now(),10,2,'APPROVED','test'),
                      (:soy2025,'SOYBEAN','FARMER','230208',DATE '2025-08-01',now(),30,2,'APPROVED','test'),
                      (:corn2026,'CORN','FARMER','230208',DATE '2026-08-01',now(),20,2,'APPROVED','test'),
                      (:draft2024,'CORN','FARMER','230208',DATE '2024-08-01',now(),999,2,'DRAFT','test')
                """).param("corn2025", UUID.randomUUID().toString())
                .param("soy2025", UUID.randomUUID().toString())
                .param("corn2026", UUID.randomUUID().toString())
                .param("draft2024", UUID.randomUUID().toString()).update();

        mvc.perform(get("/api/v1/overview/options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.years[0]").value(2026))
                .andExpect(jsonPath("$.data.years[1]").value(2025))
                .andExpect(jsonPath("$.data.years[?(@ == 2024)]").isEmpty());
        mvc.perform(get("/api/v1/overview/dashboard")
                        .queryParam("productCode", "CORN")
                        .queryParam("regionCode", "230200")
                        .queryParam("year", "2025"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.metrics[?(@.code == 'PRODUCTION_CULTIVATED_AREA')].value")
                        .value(org.hamcrest.Matchers.hasItem("10")))
                .andExpect(jsonPath("$.data.productStructure.length()").value(1))
                .andExpect(jsonPath("$.data.productStructure[0].productCode").value("CORN"));

        mvc.perform(get("/api/v1/overview/dashboard")
                        .queryParam("productCode", "CORN")
                        .queryParam("regionCode", "230200")
                        .queryParam("year", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.metrics[?(@.code == 'PRODUCTION_CULTIVATED_AREA')].value")
                        .value(org.hamcrest.Matchers.hasItem("20")));

        mvc.perform(get("/api/v1/overview/dashboard-summary")
                        .queryParam("productCode", "CORN")
                        .queryParam("regionCode", "230200")
                        .queryParam("year", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contractVersion").value("overview-audit-v2"))
                .andExpect(jsonPath("$.data.metrics[?(@.code == 'PRODUCTION_CULTIVATED_AREA')].value")
                        .value(org.hamcrest.Matchers.hasItem("20")))
                .andExpect(jsonPath("$.data.scope.prefectureCount").value(1))
                .andExpect(jsonPath("$.data.metrics[?(@.code == 'REGION_SURPLUS')]").isEmpty())
                .andExpect(jsonPath("$.data.scope.approvedRecordCount").doesNotExist())
                .andExpect(jsonPath("$.data.priceTrend").doesNotExist())
                .andExpect(jsonPath("$.data.productStructure").doesNotExist())
                .andExpect(jsonPath("$.data.regionActivity").doesNotExist())
                .andExpect(jsonPath("$.data.cultivatedAreaYoY").doesNotExist())
                .andExpect(jsonPath("$.data.outputYoY").doesNotExist())
                .andExpect(jsonPath("$.data.businessTables").doesNotExist());

        mvc.perform(get("/api/v1/overview/dashboard-summary")
                        .queryParam("productCode", "CORN")
                        .queryParam("year", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.metrics[?(@.code == 'PRODUCTION_CULTIVATED_AREA')].value")
                        .value(org.hamcrest.Matchers.hasItem("20")))
                .andExpect(jsonPath("$.data.metrics[?(@.code == 'PRODUCTION_CULTIVATED_AREA')].sourceCount")
                        .value(org.hamcrest.Matchers.hasItem(1)));

        mvc.perform(get("/api/v1/overview/dashboard")
                        .queryParam("productCode", "RICE")
                        .queryParam("regionCode", "230200")
                        .queryParam("year", "2025"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.metrics[?(@.code == 'PRODUCTION_CULTIVATED_AREA')].value")
                        .value(org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.nullValue())))
                .andExpect(jsonPath("$.data.metrics[?(@.code == 'PRODUCTION_CULTIVATED_AREA')].sourceCount")
                        .value(org.hamcrest.Matchers.hasItem(0)));
    }

    @Test
    void aggregatesOnlyApprovedFactsAcrossTheSelectedRegionHierarchy() throws Exception {
        jdbc.sql("""
                INSERT INTO production.production_record(record_id,product_code,object_type_code,region_code,survey_date,
                  reported_at,cultivated_area_mu,yield_per_mu_kg,status_code,last_modified_by)
                VALUES(:approved,'CORN','FARMER','230208',DATE '2026-08-01',now(),10,20,'APPROVED','test'),
                      (:excluded,'CORN','FARMER','230202',DATE '2026-08-01',now(),500,500,'APPROVED','test'),
                      (:draft,'CORN','FARMER','230208',DATE '2026-08-01',now(),99,99,'DRAFT','test')
                """).param("approved", UUID.randomUUID().toString())
                .param("excluded", UUID.randomUUID().toString()).param("draft", UUID.randomUUID().toString()).update();
        jdbc.sql("""
                INSERT INTO market.market_record(record_id,product_code,object_type_code,region_code,trade_date,reported_at,
                  purchase_base_price,trade_direction,carriage_board_amount,freight_amount,status_code,last_modified_by)
                VALUES(:approved,'CORN','TRADER','230208',DATE '2026-08-02',now(),2000,'PURCHASE',30,20,'APPROVED','test'),
                      (:excluded,'CORN','TRADER','230202',DATE '2026-08-02',now(),9999,'PURCHASE',0,0,'APPROVED','test'),
                      (:draft,'CORN','TRADER','230208',DATE '2026-08-02',now(),9999,'PURCHASE',0,0,'DRAFT','test')
                """).param("approved", UUID.randomUUID().toString())
                .param("excluded", UUID.randomUUID().toString()).param("draft", UUID.randomUUID().toString()).update();
        jdbc.sql("""
                INSERT INTO logistics.logistics_node(node_code,node_name,node_type_code,region_code)
                VALUES('OV-A','A','RAIL_NODE','231100'),('OV-B','B','RAIL_NODE','230208')
                """).update();
        String eventId = UUID.randomUUID().toString();
        jdbc.sql("""
                INSERT INTO logistics.route_event(event_id,product_code,monitoring_period_code,collection_date,reported_at,
                  origin_region_code,origin_node_id,origin_node_code,destination_region_code,destination_node_id,destination_node_code,
                  transport_mode_code,direction_code,source_organization,reporter,status_code,created_by,last_modified_by,created_at,updated_at,
                  business_region_code,survey_year,survey_month,survey_period_precision,survey_period_governance_state)
                SELECT CAST(:id AS uuid),'CORN','2026-Q3',DATE '2026-08-03',now(),'231100',origin.node_id,origin.node_code,
                  '230208',destination.node_id,destination.node_code,'RAIL','INFLOW','测试','test','APPROVED','test','test',now(),now(),
                  '230208',2026,8,'YEAR_MONTH','CONFIRMED'
                FROM logistics.logistics_node origin CROSS JOIN logistics.logistics_node destination
                WHERE origin.node_code='OV-A' AND destination.node_code='OV-B'
                """).param("id", eventId).update();
        jdbc.sql("INSERT INTO logistics.route_fact(event_id,fact_code,value,unit_code) VALUES(CAST(:id AS uuid),'ROUTE_VOLUME',2,'万吨')")
                .param("id", eventId).update();

        mvc.perform(get("/api/v1/overview/options"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.products[0].code").value("CORN"))
                .andExpect(jsonPath("$.data.periods[?(@.code == '2026-Q3')]").isNotEmpty());
        mvc.perform(get("/api/v1/overview/regions").queryParam("productCode", "CORN").queryParam("periodCode", "2026-Q3"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].code").value("230200"))
                .andExpect(jsonPath("$.data[0].boundaryGeoJson").isString());
        mvc.perform(get("/api/v1/overview/regions").queryParam("productCode", "CORN")
                        .queryParam("periodCode", "2026-Q3").queryParam("parentCode", "230200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.code == '230208')]").isNotEmpty())
                .andExpect(jsonPath("$.data[?(@.code == '230208')].mapContextOnly")
                        .value(org.hamcrest.Matchers.hasItem(false)))
                .andExpect(jsonPath("$.data[?(@.code == '230202')].mapContextOnly")
                        .value(org.hamcrest.Matchers.hasItem(false)))
                .andExpect(jsonPath("$.data[?(@.code == '230203')].mapContextOnly")
                        .value(org.hamcrest.Matchers.hasItem(false)))
                .andExpect(jsonPath("$.data[?(@.code == '230204')].mapContextOnly")
                        .value(org.hamcrest.Matchers.hasItem(false)))
                .andExpect(jsonPath("$.data[?(@.code == '230205')].mapContextOnly")
                        .value(org.hamcrest.Matchers.hasItem(false)))
                .andExpect(jsonPath("$.data[?(@.code == '230206')].mapContextOnly")
                        .value(org.hamcrest.Matchers.hasItem(false)))
                .andExpect(jsonPath("$.data[?(@.code == '230207')].mapContextOnly")
                        .value(org.hamcrest.Matchers.hasItem(false)))
                .andExpect(jsonPath("$.data[?(@.mapContextOnly == true)]").isEmpty());
        mvc.perform(get("/api/v1/overview/indicators").queryParam("productCode", "CORN")
                        .queryParam("regionCode", "230200").queryParam("periodCode", "2026-Q3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contractVersion").value("overview-audit-v2"))
                .andExpect(jsonPath("$.data[0].value").value("10"))
                .andExpect(jsonPath("$.data[0].formula").value("核定种植面积合计"))
                .andExpect(jsonPath("$.data[0].sourceRelation").value("产情核定记录"))
                .andExpect(jsonPath("$.data[0].dataCutoff").isString())
                .andExpect(jsonPath("$.data[0].coverageScope")
                        .value("所选地区及全部下级地区、所选产品、2026年度"))
                .andExpect(jsonPath("$.data[0].coverageStatus").value("AVAILABLE"))
                .andExpect(jsonPath("$.data[0].calculationVersion").value("总揽指标口径第1版"))
                .andExpect(jsonPath("$.data[1].value").value("200"))
                .andExpect(jsonPath("$.data[2].value").value("2050"))
                .andExpect(jsonPath("$.data[3].value").value("20000"))
                .andExpect(jsonPath("$.data[3].sourceCount").value(1))
                .andExpect(jsonPath("$.data[0].sourceCount").value(1))
                .andExpect(jsonPath("$.data[?(@.sourceDomain == 'SUPPLY')]").isEmpty())
                .andExpect(jsonPath("$.data[?(@.code == 'REGION_SURPLUS')]").isEmpty());

        mvc.perform(get("/api/v1/overview/dashboard").queryParam("productCode", "CORN")
                        .queryParam("regionCode", "230200").queryParam("year", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contractVersion").value("overview-audit-v2"))
                .andExpect(jsonPath("$.data.metrics[?(@.code == 'PRODUCTION_CULTIVATED_AREA')].formula")
                        .value(org.hamcrest.Matchers.hasItem("核定种植面积合计")))
                .andExpect(jsonPath("$.data.metrics[?(@.code == 'PRODUCTION_CULTIVATED_AREA')].coverageScope")
                        .value(org.hamcrest.Matchers.hasItem(
                                "所选地区及全部下级地区、所选产品、2026年度")))
                .andExpect(jsonPath("$.data.metrics[?(@.code == 'PRODUCTION_CULTIVATED_AREA')].calculationVersion")
                        .value(org.hamcrest.Matchers.hasItem("总揽指标口径第1版")))
                .andExpect(jsonPath(
                        "$.data.businessTables[?(@.code == 'LOGISTICS')].rows[?(@.regionCode == '230208')].values.LOG_TRANSPORT_MODE.value")
                        .value(org.hamcrest.Matchers.hasItem("铁路")))
                .andExpect(jsonPath(
                        "$.data.businessTables[?(@.code == 'LOGISTICS')].rows[?(@.regionCode == '230208')].values.LOG_DIRECTION.value")
                        .value(org.hamcrest.Matchers.hasItem("流入")))
                .andExpect(jsonPath(
                        "$.data.businessTables[?(@.code == 'LOGISTICS')].rows[?(@.regionCode == '230208')].values.LOG_ROUTE_VOLUME.value")
                        .value(org.hamcrest.Matchers.hasItem("20000")));
    }

    @Test
    void databaseRejectsAContractStateWithNoCurrentMatch() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql("""
                        UPDATE overview.region_surplus_calculation_contract
                        SET effective_from=clock_timestamp()+interval '1 day'
                        WHERE version_code='REGION_SURPLUS_V1'
                        """).update())
                .hasRootCauseInstanceOf(java.sql.SQLException.class)
                .hasMessageContaining("current time must match exactly one active");
    }

    private void approvalEvent(String aggregateType, String aggregateId, String actionCode, String occurredAt) {
        jdbc.sql("""
                INSERT INTO platform.business_event_outbox(
                  event_id,aggregate_type,aggregate_id,action_code,actor_subject_id,work_unit_code,
                  region_codes,product_code,occurred_at,detail)
                VALUES(CAST(:eventId AS uuid),:aggregateType,:aggregateId,:actionCode,'test','TEST_UNIT',
                  ARRAY['230208']::varchar(18)[],'CORN',CAST(:occurredAt AS timestamptz),'{}'::jsonb)
                """).param("eventId", UUID.randomUUID().toString())
                .param("aggregateType", aggregateType).param("aggregateId", aggregateId)
                .param("actionCode", actionCode).param("occurredAt", occurredAt).update();
    }

    private String createAndApprovePublicMarketInventory() throws Exception {
        UUID evidenceId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO overview.administrative_boundary(
                  region_code,geometry,source_name,source_url,source_revision,source_license,geometry_sha256)
                VALUES('230208',ST_Multi(ST_Buffer(ST_SetSRID(ST_MakePoint(123,47),4326),0.04)),
                  'inventory chain fixture','https://example.invalid/inventory-chain','test','test',repeat('8',64))
                """).update();
        jdbc.sql("SELECT overview.refresh_administrative_boundary_render()")
                .query(Object.class).single();
        jdbc.sql("""
                INSERT INTO evidence.evidence_photo(photo_id,state_code,original_filename,media_type,
                  original_bytes,watermarked_bytes,byte_length,sha256,captured_at,capture_latitude,
                  capture_longitude,watermark_text,uploaded_by,uploaded_at)
                VALUES(:id,'STAGED','overview-market-inventory.png','image/png',decode('00','hex'),
                  decode('01','hex'),1,encode(sha256(decode('00','hex')),'hex'),now(),47,123,
                  '地区余粮市场库存测试','market-tester',now())
                """).param("id", evidenceId).update();
        String body = """
                {"productCode":"CORN","coreValues":{
                  "MKT_OBJECT_TYPE":"TRADER","MKT_REGION":"230208",
                  "MKT_TRADE_DATE":"2026-08-10",
                  "MKT_PURCHASE_BASE_PRICE":"2300","MKT_SALE_BASE_PRICE":"2380",
                  "MKT_CARRIAGE_BOARD_AMOUNT":"36","MKT_PACKAGING_AMOUNT":"12",
                  "MKT_FREIGHT_AMOUNT":"72","MKT_PACKAGING_FORM":"BULK",
                  "MKT_SURVEYOR_NAME":"王雷","MKT_SURVEYOR_PHONE":"13800000000",
                  "MKT_SAMPLE_NAME":"地区余粮市场样本点",
                  "MKT_SAMPLE_CONTACT":"13900000000","MKT_SAMPLE_LATITUDE":"47",
                  "MKT_SAMPLE_LONGITUDE":"123"},
                  "facts":{"ENDING_INVENTORY":"20"},"evidencePhotoIds":["%s"]}
                """.formatted(evidenceId);
        String id = mvc.perform(post("/api/v1/market-records")
                .principal(() -> "market-tester")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.inventoryGovernanceStatus").doesNotExist())
                .andExpect(jsonPath("$.data.coreValues.MKT_INVENTORY_HOLDER_CODE").doesNotExist())
                .andReturn().getResponse().getContentAsString()
                .replaceFirst("(?s).*?\\\"id\\\":\\\"([^\\\"]+)\\\".*", "$1");
        mvc.perform(post("/api/v1/market-records/{id}/submit", id)
                        .principal(() -> "market-tester")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/market-records/{id}/approve", id)
                        .principal(() -> "production-tester")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":1}"))
                .andExpect(status().isOk());
        assertThat(jdbc.sql("""
                SELECT status_code || ':' || (sample_point_id IS NOT NULL)::text
                FROM market.market_record WHERE record_id=:id
                """).param("id", id).query(String.class).single()).isEqualTo("APPROVED:true");
        assertThat(jdbc.sql("""
                SELECT count(*) FROM market.market_inventory_governance WHERE record_id=:id
                """).param("id", id).query(Long.class).single()).isZero();
        return id;
    }

    private void insertSampleCountRegions() {
        GovernedMasterDataFixtures.insertRegion(
                jdbc, "230281999", "测试乡", "230281", "TOWNSHIP", 999);
        GovernedMasterDataFixtures.insertRegion(
                jdbc, "230281999001", "测试村", "230281999", "VILLAGE", 1);
    }

    @Test
    void returnsVerifiedBoundaryGeometryForTheSelectedYear() throws Exception {
        mvc.perform(get("/api/v1/overview/regions").queryParam("productCode", "CORN")
                        .queryParam("year", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].code").value("230200"))
                .andExpect(jsonPath("$.data[0].boundaryGeoJson").isString())
                .andExpect(jsonPath("$.data[0].approvedRecordCount").value(0));
    }

    @Test
    void derivesSamplePointCountsFromTheSelectedRegionHierarchy() throws Exception {
        insertSampleCountRegions();
        jdbc.sql("""
                INSERT INTO platform.monitoring_scope_region(scope_code,region_code,included)
                VALUES('FORMAL_BUSINESS','230281999',true),('FORMAL_BUSINESS','230281999001',true)
                """).update();

        mvc.perform(get("/api/v1/overview/dashboard").queryParam("productCode", "CORN")
                        .queryParam("year", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scope.villageCount").value(1));
        mvc.perform(get("/api/v1/overview/dashboard")
                        .queryParam("productCode", "CORN").queryParam("regionCode", "230200")
                        .queryParam("year", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scope.villageCount").value(1));
        mvc.perform(get("/api/v1/overview/dashboard")
                        .queryParam("productCode", "CORN").queryParam("regionCode", "230281")
                        .queryParam("year", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scope.villageCount").value(1));
        mvc.perform(get("/api/v1/overview/dashboard")
                        .queryParam("productCode", "CORN").queryParam("regionCode", "230281999")
                        .queryParam("year", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scope.townshipCount").value(1))
                .andExpect(jsonPath("$.data.scope.villageCount").value(1));
        mvc.perform(get("/api/v1/overview/dashboard")
                        .queryParam("productCode", "CORN").queryParam("regionCode", "230281999001")
                        .queryParam("year", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scope.townshipCount").value(0))
                .andExpect(jsonPath("$.data.scope.villageCount").value(1));
    }

    @Test
    void projectsApprovedBusinessMetricsToTheValidatedCoordinateRegion() throws Exception {
        insertSampleCountRegions();
        jdbc.sql("""
                INSERT INTO platform.monitoring_scope_region(scope_code,region_code,included)
                VALUES('FORMAL_BUSINESS','230281999',true),('FORMAL_BUSINESS','230281999001',true)
                """).update();
        jdbc.sql("""
                INSERT INTO overview.administrative_boundary(
                  region_code,geometry,source_name,source_url,source_revision,source_license,geometry_sha256)
                VALUES
                  ('230281999',ST_Multi(ST_Buffer(ST_SetSRID(ST_MakePoint(124.88,48.48),4326),0.04)),
                    'spatial metric parent fixture','https://example.invalid/spatial-metric-parent',
                    'test','test',repeat('7',64)),
                  ('230281999001',ST_Multi(ST_Buffer(ST_SetSRID(ST_MakePoint(124.88,48.48),4326),0.01)),
                    'spatial metric child fixture','https://example.invalid/spatial-metric-child',
                    'test','test',repeat('6',64))
                """).update();
        String samplePointId = UUID.randomUUID().toString();
        jdbc.sql("""
                INSERT INTO registry.sample_point(
                  sample_point_id,kind_code,canonical_name,region_code,approval_state,location_state,
                  governed_point,containment_boundary_sha256,effective_from,created_by,updated_by)
                VALUES(CAST(:id AS uuid),'SURVEY_SITE','坐标归属指标测试样本点','230281999',
                  'APPROVED','VALID',ST_SetSRID(ST_MakePoint(124.88,48.48),4326),repeat('7',64),
                  DATE '2026-01-01','production-tester','production-tester')
                """).param("id", samplePointId).update();
        String productionId = UUID.randomUUID().toString();
        jdbc.sql("""
                INSERT INTO production.production_record(
                  record_id,product_code,object_type_code,region_code,survey_date,reported_at,
                  cultivated_area_mu,yield_per_mu_kg,status_code,last_modified_by,version,sample_point_id,
                  survey_period_governance_state)
                VALUES(CAST(:id AS uuid),'CORN','FARMER','230281999',DATE '2026-08-10',now(),
                  17,20,'APPROVED','overview-test',1,CAST(:samplePointId AS uuid),'CONFIRMED')
                """).param("id", productionId).param("samplePointId", samplePointId).update();
        String marketId = UUID.randomUUID().toString();
        jdbc.sql("""
                INSERT INTO market.market_record(
                  record_id,product_code,object_type_code,region_code,trade_date,reported_at,
                  purchase_base_price,sale_base_price,trade_direction,carriage_board_amount,freight_amount,
                  status_code,last_modified_by,version,sample_point_id,survey_period_governance_state)
                VALUES(CAST(:id AS uuid),'CORN','TRADER','230281999',DATE '2026-08-10',now(),
                  2600,2700,'BOTH',20,30,'APPROVED','market-tester',1,
                  CAST(:samplePointId AS uuid),'CONFIRMED')
                """).param("id", marketId).param("samplePointId", samplePointId).update();
        jdbc.sql("""
                INSERT INTO logistics.logistics_node(node_code,node_name,node_type_code,region_code)
                VALUES('SPATIAL-OV-A','坐标归属物流起点','RAIL_NODE','231100'),
                      ('SPATIAL-OV-B','坐标归属物流终点','RAIL_NODE','230281999')
                """).update();
        String logisticsId = UUID.randomUUID().toString();
        jdbc.sql("""
                INSERT INTO logistics.route_event(
                  event_id,product_code,monitoring_period_code,collection_date,reported_at,
                  origin_region_code,origin_node_id,origin_node_code,destination_region_code,
                  destination_node_id,destination_node_code,transport_mode_code,direction_code,
                  source_organization,reporter,status_code,created_by,last_modified_by,created_at,updated_at,
                  business_region_code,survey_year,survey_month,survey_period_precision,
                  survey_period_governance_state,sample_point_id)
                SELECT CAST(:id AS uuid),'CORN','2026-Q3',DATE '2026-08-10',now(),
                  '231100',origin.node_id,origin.node_code,'230281999',destination.node_id,
                  destination.node_code,'RAIL','INFLOW','坐标归属物流测试','production-tester',
                  'APPROVED','production-tester','production-tester',now(),now(),'230281999',
                  2026,8,'YEAR_MONTH','CONFIRMED',CAST(:samplePointId AS uuid)
                FROM logistics.logistics_node origin CROSS JOIN logistics.logistics_node destination
                WHERE origin.node_code='SPATIAL-OV-A' AND destination.node_code='SPATIAL-OV-B'
                """).param("id", logisticsId).param("samplePointId", samplePointId).update();
        jdbc.sql("""
                INSERT INTO logistics.route_fact(event_id,fact_code,value,unit_code)
                VALUES(CAST(:id AS uuid),'ROUTE_VOLUME',3,'吨')
                """).param("id", logisticsId).update();

        mvc.perform(get("/api/v1/overview/indicators")
                        .queryParam("productCode", "CORN")
                        .queryParam("regionCode", "230281999001")
                        .queryParam("year", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.code == 'PRODUCTION_CULTIVATED_AREA')].value")
                        .value(org.hamcrest.Matchers.hasItem("17")))
                .andExpect(jsonPath("$.data[?(@.code == 'PRODUCTION_CULTIVATED_AREA')].sourceCount")
                        .value(org.hamcrest.Matchers.hasItem(1)))
                .andExpect(jsonPath("$.data[?(@.code == 'MARKET_AVERAGE_PURCHASE_PRICE')].value")
                        .value(org.hamcrest.Matchers.hasItem("2600")))
                .andExpect(jsonPath("$.data[?(@.code == 'LOGISTICS_INFLOW_VOLUME')].value")
                        .value(org.hamcrest.Matchers.hasItem("3")));

        mvc.perform(get("/api/v1/overview/dashboard")
                        .queryParam("productCode", "CORN")
                        .queryParam("regionCode", "230281999001")
                        .queryParam("year", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                        "$.data.businessTables[?(@.code == 'PRODUCTION')].rows[0].regionCode")
                        .value(org.hamcrest.Matchers.hasItem("230281999001")))
                .andExpect(jsonPath(
                        "$.data.businessTables[?(@.code == 'PRODUCTION')].rows[0].sourceCount")
                        .value(org.hamcrest.Matchers.hasItem(1)))
                .andExpect(jsonPath(
                        "$.data.businessTables[?(@.code == 'PRODUCTION')].rows[0].values.PROD_AREA_MU.value")
                        .value(org.hamcrest.Matchers.hasItem("17")))
                .andExpect(jsonPath(
                        "$.data.businessTables[?(@.code == 'MARKET')].rows[0].sourceCount")
                        .value(org.hamcrest.Matchers.hasItem(1)))
                .andExpect(jsonPath(
                        "$.data.businessTables[?(@.code == 'MARKET')].rows[0].values.MKT_PURCHASE_BASE_PRICE.value")
                        .value(org.hamcrest.Matchers.hasItem("2600")))
                .andExpect(jsonPath(
                        "$.data.businessTables[?(@.code == 'LOGISTICS')].rows[0].sourceCount")
                        .value(org.hamcrest.Matchers.hasItem(1)))
                .andExpect(jsonPath(
                        "$.data.businessTables[?(@.code == 'LOGISTICS')].rows[0].values.LOG_ROUTE_VOLUME.value")
                        .value(org.hamcrest.Matchers.hasItem("3")));

        mvc.perform(get("/api/v1/overview/annual-comparisons")
                        .queryParam("productCode", "CORN")
                        .queryParam("regionCode", "230281999001")
                        .queryParam("surveyYear", "2026")
                        .queryParam("indicatorCode", "PRODUCTION_CULTIVATED_AREA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.points[0].businessYear").value("2026"))
                .andExpect(jsonPath("$.data.points[0].value").value(17.0));
    }

    @Test
    void keepsTheDisplayReferenceProvenanceForARealCountyRender() {
        jdbc.sql("""
                INSERT INTO overview.administrative_boundary_display_reference(
                  region_code,geometry,source_name,source_url,source_revision,source_license,
                  source_feature_id,geometry_sha256
                )
                SELECT '230208',boundary.geometry,'trusted county display fixture',
                       'https://example.invalid/trusted-county','test-reference','test-license',
                       'county-230208',repeat('4',64)
                  FROM overview.administrative_boundary boundary
                 WHERE boundary.region_code='230208'
                ON CONFLICT(region_code) DO UPDATE SET
                  geometry=EXCLUDED.geometry,
                  source_name=EXCLUDED.source_name,
                  source_url=EXCLUDED.source_url,
                  source_revision=EXCLUDED.source_revision,
                  source_license=EXCLUDED.source_license,
                  source_feature_id=EXCLUDED.source_feature_id,
                  geometry_sha256=EXCLUDED.geometry_sha256
                """).update();

        jdbc.sql("SELECT overview.refresh_administrative_boundary_render()")
                .query(Object.class).single();
        jdbc.sql("""
                UPDATE overview.administrative_boundary_render
                   SET source_name='unverified county fixture',
                       source_revision='test-source',
                       source_license='community provenance unverified'
                 WHERE region_code='230208'
                """).update();
        jdbc.sql("SELECT overview.restore_administrative_boundary_render_provenance()")
                .query(Object.class).single();

        String renderedSourceName = jdbc.sql("""
                SELECT source_name
                  FROM overview.administrative_boundary_render
                 WHERE region_code='230208'
                """).query(String.class).single();

        assertThat(renderedSourceName).startsWith("trusted county display fixture");
    }

    @Test
    void repartitionsAHoledVillageSourceIntoOneWatertightParentSurface() {
        GovernedMasterDataFixtures.insertRegion(
                jdbc, "230281998", "拓扑测试乡", "230281", "TOWNSHIP", 998);
        GovernedMasterDataFixtures.insertRegion(
                jdbc, "230281998001", "拓扑测试甲村", "230281998", "VILLAGE", 1);
        GovernedMasterDataFixtures.insertRegion(
                jdbc, "230281998002", "拓扑测试乙村", "230281998", "VILLAGE", 2);
        jdbc.sql("""
                WITH county_anchor AS (
                  SELECT ST_PointOnSurface(geometry) point
                    FROM overview.administrative_boundary_render
                   WHERE region_code='230281'
                ), township AS (
                  SELECT ST_Multi(ST_Buffer(point,0.01)) geometry FROM county_anchor
                )
                INSERT INTO overview.administrative_boundary(
                  region_code,geometry,source_name,source_url,source_revision,source_license,geometry_sha256
                )
                SELECT '230281998',geometry,'test township boundary','https://example.invalid/topology','test','test',repeat('8',64)
                  FROM township
                """).update();
        jdbc.sql("""
                WITH parent AS (
                  SELECT geometry FROM overview.administrative_boundary WHERE region_code='230281998'
                ), inner_piece AS (
                  SELECT ST_Multi(ST_Buffer(ST_PointOnSurface(geometry),0.003)) geometry FROM parent
                )
                INSERT INTO overview.administrative_boundary(
                  region_code,geometry,source_name,source_url,source_revision,source_license,geometry_sha256
                )
                SELECT '230281998001',ST_Multi(ST_Difference(parent.geometry,inner_piece.geometry)),
                       'test holed village source','https://example.invalid/topology','test','test',repeat('8',64)
                  FROM parent CROSS JOIN inner_piece
                UNION ALL
                SELECT '230281998002',inner_piece.geometry,
                       'test nested village source','https://example.invalid/topology','test','test',repeat('8',64)
                  FROM inner_piece
                """).update();
        jdbc.sql("SELECT overview.refresh_administrative_boundary_render()")
                .query(Object.class).single();

        jdbc.sql("SELECT overview.repartition_display_children_watertight('VILLAGE', ARRAY['230281998'])")
                .query(Object.class).single();

        long invalidOrHoled = jdbc.sql("""
                SELECT count(*)
                  FROM overview.administrative_boundary_render render
                 WHERE render.region_code IN ('230281998001','230281998002')
                   AND (ST_NumGeometries(render.geometry) <> 1
                     OR ST_NumInteriorRings(ST_GeometryN(render.geometry,1)) <> 0)
                """).query(Long.class).single();
        long topologyErrors = jdbc.sql("""
                WITH children AS (
                  SELECT geometry
                    FROM overview.administrative_boundary_render
                   WHERE region_code IN ('230281998001','230281998002')
                ), parent AS (
                  SELECT geometry
                    FROM overview.administrative_boundary_render
                   WHERE region_code='230281998'
                ), child_union AS (
                  SELECT ST_UnaryUnion(ST_Collect(geometry)) geometry FROM children
                )
                SELECT count(*)
                  FROM parent CROSS JOIN child_union
                 WHERE overview.has_visible_surface_gap(
                         ST_Difference(parent.geometry,child_union.geometry))
                    OR overview.has_visible_surface_gap(
                         ST_Difference(child_union.geometry,parent.geometry))
                """).query(Long.class).single();

        assertThat(invalidOrHoled).isZero();
        assertThat(topologyErrors).isZero();
    }

    @Test
    void preservesRealCountyAndTownshipSurfacesAcrossAdministrativeRenderRefreshes() {
        jdbc.sql("SELECT overview.refresh_administrative_boundary_render_source()")
                .query(Object.class).single();
        String sourceGeometry = jdbc.sql("""
                SELECT encode(ST_AsEWKB(geometry),'hex')
                  FROM overview.administrative_boundary_render
                 WHERE region_code='230202'
                """).query(String.class).single();

        jdbc.sql("SELECT overview.refresh_administrative_boundary_render()")
                .query(Object.class).single();
        String refreshedGeometry = jdbc.sql("""
                SELECT encode(ST_AsEWKB(geometry),'hex')
                  FROM overview.administrative_boundary_render
                 WHERE region_code='230202'
                """).query(String.class).single();

        assertThat(refreshedGeometry).isEqualTo(sourceGeometry);
    }

    @Test
    void closesEveryOverallMapHoleBeforePublishingTheScopeBoundary() {
        jdbc.sql("SELECT overview.refresh_administrative_boundary_render()")
                .query(Object.class).single();
        jdbc.sql("SELECT overview.refresh_monitoring_scope_boundary_render('FORMAL_BUSINESS')")
                .query(Object.class).single();

        long remainingRootHoles = jdbc.sql("""
                SELECT COALESCE(SUM(ST_NRings(render.geometry)-ST_NumGeometries(render.geometry)),0)
                  FROM platform.monitoring_scope_region member
                  JOIN platform.region region ON region.code=member.region_code
                  JOIN overview.administrative_boundary_render render ON render.region_code=region.code
                 WHERE member.scope_code='FORMAL_BUSINESS' AND member.included
                   AND region.administrative_level='PREFECTURE'
                """).query(Long.class).single();
        long remainingCountyPartitionHoles = jdbc.sql("""
                WITH county_coverage AS (
                  SELECT child.parent_code,
                         ST_Multi(ST_UnaryUnion(ST_Collect(render.geometry))) geometry
                    FROM platform.region child
                    JOIN overview.administrative_boundary_render render
                      ON render.region_code=child.code
                   WHERE child.administrative_level='COUNTY'
                   GROUP BY child.parent_code
                )
                SELECT COALESCE(SUM(
                         ST_NRings(coverage.geometry)-ST_NumGeometries(coverage.geometry)
                       ),0)
                  FROM platform.monitoring_scope_region member
                  JOIN platform.region parent ON parent.code=member.region_code
                  JOIN county_coverage coverage ON coverage.parent_code=parent.code
                 WHERE member.scope_code='FORMAL_BUSINESS' AND member.included
                   AND parent.administrative_level='PREFECTURE'
                """).query(Long.class).single();
        assertThat(remainingRootHoles).isZero();
        assertThat(remainingCountyPartitionHoles).isZero();
    }

    @Test
    void returnsSourceAttributedPointsWithGeneratedTopologyClosedDisplayBoundaries() throws Exception {
        insertSampleCountRegions();
        jdbc.sql("""
                INSERT INTO platform.monitoring_scope_region(scope_code,region_code,included)
                VALUES('FORMAL_BUSINESS','230281999',true),('FORMAL_BUSINESS','230281999001',true)
                """).update();
        jdbc.sql("""
                INSERT INTO overview.administrative_boundary(
                  region_code,geometry,source_name,source_url,source_revision,source_license,geometry_sha256
                ) VALUES(
                  '230281999',
                  ST_Multi(ST_Buffer(ST_SetSRID(ST_MakePoint(124.88,48.48),4326),0.03)),
                  'real township test fixture','https://example.invalid/township-boundary',
                  'test','test',repeat('3',64)
                )
                """).update();
        jdbc.sql("""
                INSERT INTO platform.geography_import_batch(
                  dataset_sha256,source_workbook_sha256,source_revision,township_count,village_count,coordinate_count
                ) VALUES(repeat('1',64),repeat('2',64),'test',1,1,1)
                ON CONFLICT(dataset_sha256) DO NOTHING
                """).update();
        jdbc.sql("""
                INSERT INTO platform.region_location(
                  region_code,original_coordinate,wgs84_coordinate,original_crs,target_crs,conversion_method,
                  source_name,source_url,source_revision,place_type,matched_by,match_confidence,review_status,dataset_sha256
                ) VALUES(
                  '230281999001',ST_SetSRID(ST_MakePoint(124.88,48.48),4490),
                  ST_SetSRID(ST_MakePoint(124.88,48.48),4326),'EPSG:4490','EPSG:4326','test transform',
                  'test source','https://example.invalid/place','test','行政村','exact test match','HIGH',
                  'AUTO_MATCHED_PENDING_SPATIAL_QA',repeat('1',64)
                )
                """).update();
        jdbc.sql("SELECT overview.refresh_administrative_boundary_render()")
                .query(Object.class).single();

        mvc.perform(get("/api/v1/overview/regions").queryParam("productCode", "CORN")
                        .queryParam("year", "2026")
                        .queryParam("parentCode", "230281"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.code == '230281999')].boundaryGeoJson").value(org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsString("Polygon"))))
                .andExpect(jsonPath("$.data[?(@.code == '230281999')].locationGeoJson").value(org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsString("Point"))))
                .andExpect(jsonPath("$.data[?(@.code == '230281999')].locationReviewStatus").value(org.hamcrest.Matchers.hasItem("DERIVED_FROM_VILLAGE_POINTS")));
        mvc.perform(get("/api/v1/overview/regions").queryParam("productCode", "CORN")
                        .queryParam("year", "2026")
                        .queryParam("parentCode", "230281999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].code").value("230281999001"))
                .andExpect(jsonPath("$.data[0].boundaryGeoJson").value(org.hamcrest.Matchers.containsString("Polygon")))
                .andExpect(jsonPath("$.data[0].locationGeoJson").value(org.hamcrest.Matchers.containsString("Point")))
                .andExpect(jsonPath("$.data[0].locationReviewStatus").value("AUTO_MATCHED_PENDING_SPATIAL_QA"));
        mvc.perform(get("/api/v1/overview/locations").queryParam("productCode", "CORN")
                        .queryParam("year", "2026")
                        .queryParam("ancestorCode", "230281")
                        .queryParam("level", "TOWNSHIP"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.code == '230281999')].locationReviewStatus")
                        .value(org.hamcrest.Matchers.hasItem("DERIVED_FROM_VILLAGE_POINTS")));
        mvc.perform(get("/api/v1/overview/locations").queryParam("productCode", "CORN")
                        .queryParam("year", "2026")
                        .queryParam("ancestorCode", "230281999")
                        .queryParam("level", "VILLAGE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].code").value("230281999001"))
                .andExpect(jsonPath("$.data[0].locationReviewStatus").value("AUTO_MATCHED_PENDING_SPATIAL_QA"));
        mvc.perform(get("/api/v1/overview/dashboard")
                        .queryParam("productCode", "CORN")
                        .queryParam("year", "2026")
                        .queryParam("regionCode", "230281999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scope.townshipCount").value(1))
                .andExpect(jsonPath("$.data.scope.villageCount").value(1));
        mvc.perform(get("/api/v1/overview/dashboard")
                        .queryParam("productCode", "CORN")
                        .queryParam("year", "2026")
                        .queryParam("regionCode", "230281999001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scope.townshipCount").value(0))
                .andExpect(jsonPath("$.data.scope.villageCount").value(1));
    }

    @Test
    void buildsTheCockpitOnlyFromGovernedScopeAndPlatformBusinessRecords() throws Exception {
        String productionId = UUID.randomUUID().toString();
        String priorProductionId = UUID.randomUUID().toString();
        String marketId = UUID.randomUUID().toString();
        String returnedId = UUID.randomUUID().toString();
        jdbc.sql("""
                INSERT INTO production.production_record(record_id,product_code,object_type_code,region_code,survey_date,
                  reported_at,cultivated_area_mu,yield_per_mu_kg,status_code,last_modified_by)
                VALUES(:id,'CORN','FARMER','230208',DATE '2026-08-01',TIMESTAMPTZ '2026-08-03 09:45:00+08',
                         10,20,'APPROVED','production-tester'),
                      (:prior,'CORN','FARMER','230208',DATE '2025-08-01',TIMESTAMPTZ '2025-08-03 09:45:00+08',
                         5,10,'APPROVED','production-tester')
                """).param("id", productionId).param("prior", priorProductionId).update();
        jdbc.sql("""
                INSERT INTO market.market_record(record_id,product_code,object_type_code,region_code,trade_date,reported_at,
                  purchase_base_price,sale_base_price,trade_direction,carriage_board_amount,freight_amount,status_code,return_reason,last_modified_by)
                VALUES(:approved,'CORN','TRADER','230208',DATE '2026-08-02',TIMESTAMPTZ '2026-08-03 10:15:00+08',
                         2000,2400,'PURCHASE',30,20,'APPROVED',NULL,'market-tester'),
                      (:returned,'CORN','TRADER','230208',DATE '2026-08-02',TIMESTAMPTZ '2026-08-03 10:20:00+08',
                         2100,2500,'PURCHASE',0,0,'RETURNED','价格依据需补充','market-tester')
                """).param("approved", marketId).param("returned", returnedId).update();
        jdbc.sql("""
                INSERT INTO market.market_record_fact(record_id,fact_code,value,product_code,object_type_code)
                VALUES(:id,'PURCHASE_VOLUME',12.5,'CORN','TRADER'),
                      (:id,'ENDING_INVENTORY',4,'CORN','TRADER')
                """).param("id", marketId).update();

        mvc.perform(get("/api/v1/overview/dashboard")
                        .queryParam("productCode", "CORN")
                        .queryParam("periodCode", "2026-Q3")
                        .queryParam("regionCode", "230200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scope.countyCount").value(10))
                .andExpect(jsonPath("$.data.scope.villageCount").value(0))
                .andExpect(jsonPath("$.data.scope.reportingUnitCount").value(1))
                .andExpect(jsonPath("$.data.scope.approvedRecordCount").value(2))
                .andExpect(jsonPath("$.data.metrics[?(@.code == 'PRODUCTION_CULTIVATED_AREA')].value").value("10"))
                .andExpect(jsonPath("$.data.metrics[?(@.code == 'PRODUCTION_ESTIMATED_OUTPUT')].value").value("200"))
                .andExpect(jsonPath("$.data.metrics[?(@.code == 'MARKET_AVERAGE_PURCHASE_PRICE')].value").value("2000"))
                .andExpect(jsonPath("$.data.metrics[?(@.code == 'MARKET_AVERAGE_SALE_PRICE')].value").value("2400"))
                .andExpect(jsonPath("$.data.metrics[?(@.code == 'MARKET_AVERAGE_TRADE_PRICE')]").isEmpty())
                .andExpect(jsonPath("$.data.metrics[?(@.code =~ /SUPPLY_.*/)]").isEmpty())
                .andExpect(jsonPath("$.data.metrics[?(@.code == 'REGION_SURPLUS')]").isEmpty())
                .andExpect(jsonPath("$.data.regionPath[0].code").value("230200"))
                .andExpect(jsonPath("$.data.priceTrend[0].periodLabel").value("2026-08"))
                .andExpect(jsonPath("$.data.productStructure[?(@.productCode == 'CORN')].value").value("200"))
                .andExpect(jsonPath("$.data.regionActivity[?(@.regionCode == '230208')].approvedCount").value(2))
                .andExpect(jsonPath("$.data.regionActivity[?(@.regionCode == '230208')].totalCount").value(2))
                .andExpect(jsonPath("$.data.cultivatedAreaYoY[0].regionCode").value("230208"))
                .andExpect(jsonPath("$.data.cultivatedAreaYoY[0].currentValue").value("10"))
                .andExpect(jsonPath("$.data.cultivatedAreaYoY[0].previousValue").value("5"))
                .andExpect(jsonPath("$.data.outputYoY[0].currentValue").value("200"))
                .andExpect(jsonPath("$.data.outputYoY[0].previousValue").value("50"))
                .andExpect(jsonPath("$.data.alerts.length()").value(0))
                .andExpect(jsonPath("$.data.businessTables.length()").value(3))
                .andExpect(jsonPath("$.data.businessTables[0].code").value("PRODUCTION"))
                .andExpect(jsonPath("$.data.businessTables[0].title").value("产情监测表"))
                .andExpect(jsonPath("$.data.businessTables[0].coverageStatus").value("AVAILABLE"))
                .andExpect(jsonPath(
                        "$.data.businessTables[?(@.code == 'PRODUCTION')].columns[?(@.code == 'PROD_AREA_MU')].label")
                        .value(org.hamcrest.Matchers.hasItem("种植面积")))
                .andExpect(jsonPath(
                        "$.data.businessTables[?(@.code == 'PRODUCTION')].rows[?(@.regionCode == '230208')].values.PROD_AREA_MU.value")
                        .value(org.hamcrest.Matchers.hasItem("10")))
                .andExpect(jsonPath(
                        "$.data.businessTables[?(@.code == 'PRODUCTION')].rows[?(@.regionCode == '230208')].values.PROD_YIELD_PER_MU.value")
                        .value(org.hamcrest.Matchers.hasItem("20")))
                .andExpect(jsonPath(
                        "$.data.businessTables[?(@.code == 'PRODUCTION')].rows[?(@.regionCode == '230208')].values.PROD_ESTIMATED_OUTPUT.value")
                        .value(org.hamcrest.Matchers.hasItem("200")))
                .andExpect(jsonPath(
                        "$.data.businessTables[?(@.code == 'MARKET')].rows[?(@.regionCode == '230208')].values.MKT_PURCHASE_BASE_PRICE.value")
                        .value(org.hamcrest.Matchers.hasItem("2000")))
                .andExpect(jsonPath(
                        "$.data.businessTables[?(@.code == 'MARKET')].rows[?(@.regionCode == '230208')].values.MKT_SALE_BASE_PRICE.value")
                        .value(org.hamcrest.Matchers.hasItem("2400")))
                .andExpect(jsonPath(
                        "$.data.businessTables[?(@.code == 'MARKET')].rows[?(@.regionCode == '230208')].values.PURCHASE_VOLUME.value")
                        .value(org.hamcrest.Matchers.hasItem("12.5")))
                .andExpect(jsonPath(
                        "$.data.businessTables[?(@.code == 'MARKET')].rows[?(@.regionCode == '230208')].values.ENDING_INVENTORY.value")
                        .value(org.hamcrest.Matchers.hasItem("4")))
                .andExpect(jsonPath(
                        "$.data.businessTables[?(@.code == 'LOGISTICS')].coverageStatus")
                        .value(org.hamcrest.Matchers.hasItem("NO_APPROVED_SOURCES")))
                .andExpect(jsonPath("$.data.businessTables[?(@.code == 'SUPPLY')]").isEmpty());
    }
}
