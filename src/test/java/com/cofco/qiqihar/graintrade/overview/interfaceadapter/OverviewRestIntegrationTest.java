package com.cofco.qiqihar.graintrade.overview.interfaceadapter;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
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
class OverviewRestIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired DataSource dataSource;
    JdbcClient jdbc;

    @BeforeEach
    void clean() {
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("DELETE FROM overview.administrative_boundary_display_reference WHERE region_code='230208'").update();
        jdbc.sql("DELETE FROM overview.administrative_boundary_render WHERE region_code='230208'").update();
        jdbc.sql("DELETE FROM overview.administrative_boundary WHERE region_code='230208'").update();
        jdbc.sql("DELETE FROM platform.monitoring_scope_region WHERE region_code IN ('230281999001','230281999','230281998001','230281998002','230281998','230202998001','230202998')").update();
        jdbc.sql("DELETE FROM platform.work_unit_region_scope WHERE region_code IN ('230281999001','230281999','230281998001','230281998002','230281998','230202998001','230202998')").update();
        jdbc.sql("DELETE FROM platform.security_user_region_scope WHERE region_code IN ('230281999001','230281999','230281998001','230281998002','230281998','230202998001','230202998')").update();
        jdbc.sql("DELETE FROM overview.administrative_boundary_display_reference WHERE region_code IN ('230281999001','230281999','230281998001','230281998002','230281998','230202998001','230202998')").update();
        jdbc.sql("DELETE FROM overview.administrative_boundary_render WHERE region_code IN ('230281999001','230281999','230281998001','230281998002','230281998','230202998001','230202998')").update();
        jdbc.sql("DELETE FROM overview.administrative_boundary WHERE region_code IN ('230281999001','230281999','230281998001','230281998002','230281998','230202998001','230202998')").update();
        jdbc.sql("DELETE FROM platform.region WHERE code IN ('230281999001','230281999','230281998001','230281998002','230281998','230202998001','230202998')").update();
        jdbc.sql("""
                TRUNCATE production.production_record,market.market_record,logistics.route_event,logistics.logistics_node,
                  supply.calculation_run RESTART IDENTITY CASCADE
                """).update();
        jdbc.sql("""
                INSERT INTO platform.business_period(code,name,starts_on,ends_on,sort_order,marketing_year_code)
                VALUES('2026-Q3','2026年第三季度',DATE '2026-07-01',DATE '2026-09-30',202603,'2026/27')
                ON CONFLICT(code) DO NOTHING
                """).update();
        jdbc.sql("""
                DELETE FROM overview.administrative_boundary
                 WHERE region_code IN ('230200','230202','230203','230204','230205','230206','230207')
                """).update();
        jdbc.sql("""
                INSERT INTO overview.administrative_boundary(
                  region_code,geometry,source_name,source_url,source_revision,source_license,geometry_sha256
                )
                SELECT fixture.region_code,
                       ST_Multi(ST_Buffer(ST_SetSRID(ST_MakePoint(123 + fixture.longitude_delta,47),4326),0.04)),
                       'test fixture','https://example.invalid/boundary','test','test',repeat('0',64)
                  FROM (VALUES
                    ('230200',0.00),('230202',0.10),('230203',0.20),('230204',0.30),
                    ('230205',0.40),('230206',0.50),('230207',0.60)
                  ) fixture(region_code,longitude_delta)
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
                  transport_mode_code,direction_code,source_organization,reporter,status_code,created_by,last_modified_by,created_at,updated_at)
                SELECT CAST(:id AS uuid),'CORN','2026-Q3',DATE '2026-08-03',now(),'231100',origin.node_id,origin.node_code,
                  '230208',destination.node_id,destination.node_code,'RAIL','INFLOW','测试','test','APPROVED','test','test',now(),now()
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
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].value").value("10"))
                .andExpect(jsonPath("$.data[1].value").value("200"))
                .andExpect(jsonPath("$.data[2].value").value("2050"))
                .andExpect(jsonPath("$.data[3].value").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data[3].sourceCount").value(0))
                .andExpect(jsonPath("$.data[0].sourceCount").value(1))
                .andExpect(jsonPath("$.data[5].value").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void derivesReliableRegionSurplusFromLatestApprovedMutuallyExclusiveSources() throws Exception {
        String productionId = UUID.randomUUID().toString();
        String marketId = UUID.randomUUID().toString();
        String samplePointId = UUID.randomUUID().toString();
        jdbc.sql("""
                INSERT INTO registry.sample_point(
                  sample_point_id,kind_code,canonical_name,region_code,approval_state,location_state,
                  effective_from,created_by,updated_by)
                VALUES(CAST(:id AS uuid),'SURVEY_SITE','余粮主体样本点','230208','APPROVED','MISSING',
                  DATE '2026-01-01','production-tester','production-tester')
                """).param("id", samplePointId).update();
        jdbc.sql("""
                INSERT INTO production.production_record(record_id,product_code,object_type_code,region_code,
                  survey_date,reported_at,cultivated_area_mu,yield_per_mu_kg,status_code,last_modified_by,version,
                  sample_point_id)
                VALUES(:id,'CORN','FARMER','230208',DATE '2026-08-10',
                  '2026-08-10T10:00:00+08:00',1,1,'APPROVED','test',2,CAST(:samplePointId AS uuid))
                """).param("id", productionId).param("samplePointId", samplePointId).update();
        jdbc.sql("""
                INSERT INTO production.production_record_submission_metadata(record_id,field_code,value)
                VALUES(:id,'PROD_ENDING_INVENTORY','12'),
                      (:id,'PROD_SURPLUS_SUBJECT_CODE','farmer-acceptance-1'),
                      (:id,'PROD_SURPLUS_CUTOFF_DATE','2026-08-10')
                """).param("id", productionId).update();
        approvalEvent("PRODUCTION_RECORD", productionId, "PRODUCTION_RECORD_APPROVED",
                "2026-08-10T10:00:00+08:00");

        jdbc.sql("""
                INSERT INTO market.market_record(record_id,product_code,object_type_code,region_code,trade_date,
                  reported_at,purchase_base_price,sale_base_price,trade_direction,carriage_board_amount,
                  freight_amount,status_code,last_modified_by,version)
                VALUES(:id,'CORN','TRADER','230208',DATE '2026-08-10','2026-08-10T11:00:00+08:00',
                  1,1,'BOTH',0,0,'APPROVED','test',2)
                """).param("id", marketId).update();
        jdbc.sql("""
                INSERT INTO market.market_record_fact(record_id,fact_code,value,product_code,object_type_code)
                VALUES(:id,'ENDING_INVENTORY',20,'CORN','TRADER')
                """).param("id", marketId).update();
        jdbc.sql("""
                INSERT INTO market.market_record_core_value(
                  record_id,product_code,field_code,domain_binding,value)
                VALUES(:id,'CORN','MKT_INVENTORY_HOLDER_CODE','EXTENSION','enterprise-owner-1'),
                      (:id,'CORN','MKT_INVENTORY_OWNERSHIP_TYPE','EXTENSION','OWNED'),
                      (:id,'CORN','MKT_STORAGE_REGION_CODE','EXTENSION','230208'),
                      (:id,'CORN','MKT_CARGO_OWNER_CODE','EXTENSION','enterprise-owner-1'),
                      (:id,'CORN','MKT_INVENTORY_CUTOFF_DATE','EXTENSION','2026-08-10'),
                      (:id,'CORN','MKT_INVENTORY_POLICY_ATTRIBUTE','EXTENSION','POLICY_AND_COMMERCIAL')
                """).param("id", marketId).update();
        approvalEvent("MARKET_RECORD", marketId, "MARKET_RECORD_APPROVED",
                "2026-08-10T11:00:00+08:00");

        mvc.perform(get("/api/v1/overview/dashboard")
                        .queryParam("productCode", "CORN")
                        .queryParam("regionCode", "230200")
                        .queryParam("periodCode", "2026-Q3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.metrics[?(@.code == 'REGION_SURPLUS')].value")
                        .value(org.hamcrest.Matchers.hasItem("32")))
                .andExpect(jsonPath("$.data.metrics[?(@.code == 'REGION_SURPLUS')].sourceCount")
                        .value(org.hamcrest.Matchers.hasItem(2)))
                .andExpect(jsonPath("$.data.metrics[?(@.code == 'REGION_SURPLUS')].dataCutoff")
                        .value(org.hamcrest.Matchers.hasItem("2026-08-10")))
                .andExpect(jsonPath("$.data.metrics[?(@.code == 'REGION_SURPLUS')].coverageStatus")
                        .value(org.hamcrest.Matchers.hasItem("AVAILABLE")))
                .andExpect(jsonPath("$.data.metrics[?(@.code == 'REGION_SURPLUS')].calculationVersion")
                        .value(org.hamcrest.Matchers.hasItem("REGION_SURPLUS_V1")))
                .andExpect(jsonPath("$.data.metrics[?(@.code == 'REGION_SURPLUS')].auditSources.length()")
                        .value(org.hamcrest.Matchers.hasItem(2)))
                .andExpect(jsonPath("$.data.metrics[?(@.code == 'REGION_SURPLUS')].auditSources[*].subjectKey")
                        .value(org.hamcrest.Matchers.hasItem("farmer-acceptance-1")));
    }

    @Test
    void exposesUnavailableRegionSurplusInsteadOfAFalseZeroWhenCoverageIsIncomplete() throws Exception {
        String productionId = UUID.randomUUID().toString();
        String marketId = UUID.randomUUID().toString();
        jdbc.sql("""
                INSERT INTO production.production_record(record_id,product_code,object_type_code,region_code,
                  survey_date,reported_at,cultivated_area_mu,yield_per_mu_kg,status_code,last_modified_by,version)
                VALUES(:id,'CORN','FARMER','230208',DATE '2026-08-10',
                  '2026-08-10T10:00:00+08:00',1,1,'APPROVED','test',2)
                """).param("id", productionId).update();
        jdbc.sql("""
                INSERT INTO production.production_record_submission_metadata(record_id,field_code,value)
                VALUES(:id,'PROD_ENDING_INVENTORY','12'),
                      (:id,'PROD_SURPLUS_SUBJECT_CODE','farmer-acceptance-1'),
                      (:id,'PROD_SURPLUS_CUTOFF_DATE','2026-08-10')
                """).param("id", productionId).update();
        approvalEvent("PRODUCTION_RECORD", productionId, "PRODUCTION_RECORD_APPROVED",
                "2026-08-10T10:00:00+08:00");

        mvc.perform(get("/api/v1/overview/dashboard")
                        .queryParam("productCode", "CORN")
                        .queryParam("regionCode", "230200")
                        .queryParam("periodCode", "2026-Q3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.metrics[?(@.code == 'REGION_SURPLUS')].value")
                        .value(org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.nullValue())))
                .andExpect(jsonPath("$.data.metrics[?(@.code == 'REGION_SURPLUS')].sourceCount")
                        .value(org.hamcrest.Matchers.hasItem(0)))
                .andExpect(jsonPath("$.data.metrics[?(@.code == 'REGION_SURPLUS')].coverageStatus")
                        .value(org.hamcrest.Matchers.hasItem("INSUFFICIENT_COVERAGE")));

        jdbc.sql("""
                INSERT INTO market.market_record(record_id,product_code,object_type_code,region_code,trade_date,
                  reported_at,purchase_base_price,sale_base_price,trade_direction,carriage_board_amount,
                  freight_amount,status_code,last_modified_by,version)
                VALUES(:id,'CORN','TRADER','230208',DATE '2026-08-10','2026-08-10T11:00:00+08:00',
                  1,1,'BOTH',0,0,'APPROVED','test',2)
                """).param("id", marketId).update();
        jdbc.sql("""
                INSERT INTO market.market_record_fact(record_id,fact_code,value,product_code,object_type_code)
                VALUES(:id,'ENDING_INVENTORY',20,'CORN','TRADER')
                """).param("id", marketId).update();
        jdbc.sql("""
                INSERT INTO market.market_record_core_value(
                  record_id,product_code,field_code,domain_binding,value)
                VALUES(:id,'CORN','MKT_INVENTORY_HOLDER_CODE','EXTENSION','enterprise-owner-1'),
                      (:id,'CORN','MKT_INVENTORY_OWNERSHIP_TYPE','EXTENSION','OWNED'),
                      (:id,'CORN','MKT_CARGO_OWNER_CODE','EXTENSION','enterprise-owner-1'),
                      (:id,'CORN','MKT_INVENTORY_CUTOFF_DATE','EXTENSION','2026-08-10'),
                      (:id,'CORN','MKT_INVENTORY_POLICY_ATTRIBUTE','EXTENSION','COMMERCIAL')
                """).param("id", marketId).update();
        approvalEvent("MARKET_RECORD", marketId, "MARKET_RECORD_APPROVED",
                "2026-08-10T11:00:00+08:00");

        mvc.perform(get("/api/v1/overview/dashboard")
                        .queryParam("productCode", "CORN")
                        .queryParam("regionCode", "230200")
                        .queryParam("periodCode", "2026-Q3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.metrics[?(@.code == 'REGION_SURPLUS')].value")
                        .value(org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.nullValue())))
                .andExpect(jsonPath("$.data.metrics[?(@.code == 'REGION_SURPLUS')].coverageStatus")
                        .value(org.hamcrest.Matchers.hasItem("UNRELIABLE_SOURCE_CONTRACT")));
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
        jdbc.sql("""
                INSERT INTO platform.region(code,name,parent_code,administrative_level,sort_order)
                VALUES('230281999','测试乡','230281','TOWNSHIP',999),
                      ('230281999001','测试村','230281999','VILLAGE',1)
                """).update();
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
    void keepsTheDisplayReferenceProvenanceForARealCountyRender() {
        jdbc.sql("""
                INSERT INTO overview.administrative_boundary(
                  region_code,geometry,source_name,source_url,source_revision,source_license,geometry_sha256
                ) VALUES(
                  '230208',
                  ST_Multi(ST_Buffer(ST_SetSRID(ST_MakePoint(123.02,47),4326),0.01)),
                  'unverified county fixture','https://example.invalid/unverified-county',
                  'test-source','community provenance unverified',repeat('5',64)
                )
                """).update();
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
        jdbc.sql("""
                INSERT INTO platform.region(code,name,parent_code,administrative_level,sort_order)
                VALUES('230281998','拓扑测试乡','230281','TOWNSHIP',998),
                      ('230281998001','拓扑测试甲村','230281998','VILLAGE',1),
                      ('230281998002','拓扑测试乙村','230281998','VILLAGE',2)
                """).update();
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
        jdbc.sql("SELECT overview.refresh_administrative_boundary_render()")
                .query(Object.class).single();
        jdbc.sql("""
                WITH parent AS (
                  SELECT geometry FROM overview.administrative_boundary_render WHERE region_code='230281998'
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
                 WHERE ST_Area(ST_Difference(parent.geometry,child_union.geometry)::geography) > 1
                    OR ST_Area(ST_Difference(child_union.geometry,parent.geometry)::geography) > 1
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
        jdbc.sql("""
                UPDATE overview.administrative_boundary
                   SET geometry=ST_Multi(ST_GeomFromText(
                     'POLYGON((123 47,124 47,124 48,123 48,123 47),(123.4 47.4,123.6 47.4,123.6 47.6,123.4 47.6,123.4 47.4))',
                     4326
                   ))
                 WHERE region_code='230200'
                """).update();

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
        assertThat(remainingRootHoles).isZero();
    }

    @Test
    void returnsSourceAttributedPointsWithGeneratedTopologyClosedDisplayBoundaries() throws Exception {
        jdbc.sql("""
                INSERT INTO platform.region(code,name,parent_code,administrative_level,sort_order)
                VALUES('230281999','测试乡','230281','TOWNSHIP',999),
                      ('230281999001','测试村','230281999','VILLAGE',1)
                """).update();
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
                  purchase_base_price,trade_direction,carriage_board_amount,freight_amount,status_code,return_reason,last_modified_by)
                VALUES(:approved,'CORN','TRADER','230208',DATE '2026-08-02',TIMESTAMPTZ '2026-08-03 10:15:00+08',
                         2000,'PURCHASE',30,20,'APPROVED',NULL,'market-tester'),
                      (:returned,'CORN','TRADER','230208',DATE '2026-08-02',TIMESTAMPTZ '2026-08-03 10:20:00+08',
                         2100,'PURCHASE',0,0,'RETURNED','价格依据需补充','market-tester')
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
                .andExpect(jsonPath("$.data.metrics[?(@.code == 'MARKET_AVERAGE_TRADE_PRICE')].value").value("2050"))
                .andExpect(jsonPath("$.data.metrics[?(@.code == 'SUPPLY_TOTAL_SUPPLY')].sourceCount").value(0))
                .andExpect(jsonPath("$.data.metrics[?(@.code == 'SUPPLY_TOTAL_USE')].sourceCount").value(0))
                .andExpect(jsonPath("$.data.metrics[?(@.code == 'SUPPLY_ADOPTED_ENDING_INVENTORY')].sourceCount").value(0))
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
                .andExpect(jsonPath("$.data.alerts.length()").value(0));
    }
}
