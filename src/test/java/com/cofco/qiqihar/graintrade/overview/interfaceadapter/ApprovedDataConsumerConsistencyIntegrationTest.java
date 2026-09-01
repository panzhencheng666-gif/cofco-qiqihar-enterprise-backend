package com.cofco.qiqihar.graintrade.overview.interfaceadapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.testsupport.ProtectedTestDatabaseConfiguration;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes = GrainTradeApplication.class)
@AutoConfigureMockMvc
@UsesProtectedTestDatabase
class ApprovedDataConsumerConsistencyIntegrationTest {
    private static final String ACTOR = "production-tester";
    private static final String APPROVED = "99000000-0000-0000-0000-000000000001";
    private static final String LATEST_APPROVED = "99000000-0000-0000-0000-000000000003";
    private static final UUID FORMAL_SAMPLE_POINT =
            UUID.fromString("99000000-0000-0000-0000-000000000004");

    @Autowired MockMvc mvc;
    @Autowired DataSource dataSource;
    private JdbcClient jdbc;

    @BeforeEach
    void setUpOneApprovedSnapshotAndOneExcludedDraft() {
        jdbc = JdbcClient.create(dataSource);
        clearSnapshot();
        ProtectedTestDatabaseConfiguration.provisionSecurityTestSubjects(jdbc);
        jdbc.sql("""
                INSERT INTO overview.administrative_boundary(
                  region_code,geometry,source_name,source_url,source_revision,source_license,
                  source_feature_id,source_effective_on,geometry_sha256)
                VALUES('230200',ST_Multi(ST_MakeEnvelope(123.5,47.0,124.3,48.0,4326)),
                  'consumer consistency fixture','urn:test:consumer-consistency','test-v1',
                  'Test fixture','230200',DATE '2026-08-19',
                  encode(sha256(ST_AsEWKB(ST_Multi(ST_MakeEnvelope(123.5,47.0,124.3,48.0,4326)))),'hex'))
                ON CONFLICT(region_code) DO NOTHING
                """).update();
        jdbc.sql("""
                INSERT INTO platform.business_period(
                  code,name,starts_on,ends_on,sort_order,marketing_year_code)
                VALUES('2026-Q3','2026年第三季度',DATE '2026-07-01',DATE '2026-09-30',202603,'2026/27')
                ON CONFLICT(code) DO NOTHING
                """).update();
        jdbc.sql("""
                INSERT INTO registry.sample_point(
                  sample_point_id,kind_code,canonical_name,region_code,approval_state,location_state,
                  governed_point,effective_from,created_by,updated_by)
                SELECT :id,'SURVEY_SITE','消费者一致性正式样本点','230208','APPROVED','VALID',
                  ST_PointOnSurface(boundary.geometry),DATE '2026-01-01',:actor,:actor
                FROM overview.administrative_boundary boundary WHERE boundary.region_code='230208'
                """).param("id", FORMAL_SAMPLE_POINT).param("actor", ACTOR).update();
        jdbc.sql("""
                INSERT INTO production.production_record(
                  record_id,product_code,object_type_code,region_code,survey_date,reported_at,
                  cultivated_area_mu,yield_per_mu_kg,status_code,last_modified_by,
                  survey_period_governance_state,sample_point_id)
                VALUES(:approved,'CORN','FARMER','230208',DATE '2026-08-09',
                    TIMESTAMPTZ '2026-08-09 12:34:56+08',10,20,'APPROVED',:actor,'CONFIRMED',:samplePointId),
                  ('99000000-0000-0000-0000-000000000002','CORN','FARMER','230208',
                    DATE '2026-08-09',TIMESTAMPTZ '2026-08-09 13:00:00+08',999,999,'DRAFT',:actor,'CONFIRMED',NULL)
                """).param("approved", APPROVED).param("actor", ACTOR)
                .param("samplePointId", FORMAL_SAMPLE_POINT).update();
        jdbc.sql("""
                UPDATE production.production_record
                SET updated_at=TIMESTAMPTZ '2026-08-09 12:34:56+08'
                WHERE record_id=:approved
                """).param("approved", APPROVED).update();
    }

    @AfterEach
    void tearDownApprovedSnapshot() {
        clearSnapshot();
    }

    private void clearSnapshot() {
        jdbc.sql("""
                TRUNCATE reporting.report_audit_event,reporting.report_publication,
                  reporting.report_export_task,reporting.report_preview,reporting.approved_dataset,
                  platform.business_audit_event,platform.business_event_outbox,
                  production.production_record RESTART IDENTITY CASCADE
                """).update();
        jdbc.sql("DELETE FROM registry.sample_point WHERE sample_point_id=:id")
                .param("id", FORMAL_SAMPLE_POINT).update();
    }

    @Test
    void listDetailAnalysisOverviewAndExportUseTheSameApprovedSnapshot() throws Exception {
        mvc.perform(get("/api/v1/production-records").principal(() -> ACTOR)
                        .queryParam("productCode", "CORN").queryParam("pageKind", "MONITORING")
                        .queryParam("pageNumber", "0").queryParam("pageSize", "20")
                        .queryParam("filter.status", "APPROVED").queryParam("filter.regionCode", "230208"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(APPROVED))
                .andExpect(jsonPath("$.data.items[0].values.PROD_AREA_MU").value("10.0000"));
        mvc.perform(get("/api/v1/production-records/{id}", APPROVED).principal(() -> ACTOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.cultivatedAreaMu").value("10.0000"));

        mvc.perform(get("/api/v1/overview/annual-comparisons").principal(() -> ACTOR)
                        .queryParam("productCode", "CORN").queryParam("regionCode", "230208")
                        .queryParam("surveyYear", "2026")
                        .queryParam("indicatorCode", "PRODUCTION_CULTIVATED_AREA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.points[0].businessYear").value("2026"))
                .andExpect(jsonPath("$.data.points[0].value").value(10.0))
                .andExpect(jsonPath("$.data.points[0].sourcePublicationVersion")
                        .value("APPROVED_PRODUCTION_RECORD:v0"));
        mvc.perform(get("/api/v1/overview/indicators").principal(() -> ACTOR)
                        .queryParam("productCode", "CORN").queryParam("regionCode", "230208")
                        .queryParam("year", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].value").value("10"))
                .andExpect(jsonPath("$.data[0].sourceCount").value(1));
        mvc.perform(get("/api/v1/overview/dashboard").principal(() -> ACTOR)
                        .queryParam("productCode", "CORN").queryParam("regionCode", "230208")
                        .queryParam("year", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.metrics[?(@.code == 'PRODUCTION_CULTIVATED_AREA')].value")
                        .value(org.hamcrest.Matchers.hasItem("10")))
                .andExpect(jsonPath("$.data.metrics[?(@.code == 'PRODUCTION_CULTIVATED_AREA')].sourceCount")
                        .value(org.hamcrest.Matchers.hasItem(1)));

        String request = "{\"definitionCode\":\"PRODUCTION_DAILY\",\"productCode\":\"CORN\","
                + "\"regionLevel\":\"COUNTY\",\"regionCode\":\"230208\",\"periodCode\":\"2026-Q3\"}";
        String preview = mvc.perform(post("/api/v1/reports/previews").principal(() -> ACTOR)
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.lines[0].value").value("1"))
                .andExpect(jsonPath("$.data.lines[?(@.label == '数据截止时间')].value")
                        .value(org.hamcrest.Matchers.hasItem("2026年08月09日 12:34:56")))
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\\\"id\\\":\\\"([^\\\"]+).*", "$1");
        String export = mvc.perform(post("/api/v1/reports/previews/{id}/exports", preview)
                        .principal(() -> ACTOR).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"formatCode\":\"CSV\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()
                .replaceAll(".*\\\"id\\\":\\\"([^\\\"]+).*", "$1");
        mvc.perform(get("/api/v1/reports/exports/{id}/content", export).principal(() -> ACTOR))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"核定数据条数\",\"1\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("2026年08月09日 12:34:56")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("production.production_record"))));
    }

    @Test
    void overviewEndpointsUseTheSamePublishedSampleHeadlineReadModel() throws Exception {
        String originalBoundaryHash = jdbc.sql("""
                SELECT geometry_sha256 FROM overview.administrative_boundary
                WHERE region_code='230208'
                """).query(String.class).single();
        try {
            jdbc.sql("""
                    UPDATE overview.administrative_boundary
                    SET geometry_sha256=repeat('0',64)
                    WHERE region_code='230208'
                    """).update();

            String indicators = mvc.perform(get("/api/v1/overview/indicators").principal(() -> ACTOR)
                            .queryParam("productCode", "CORN").queryParam("regionCode", "230208")
                            .queryParam("year", "2026"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            String summary = mvc.perform(get("/api/v1/overview/dashboard-summary").principal(() -> ACTOR)
                            .queryParam("productCode", "CORN").queryParam("regionCode", "230208")
                            .queryParam("year", "2026"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            ObjectMapper json = new ObjectMapper();
            JsonNode indicatorData = json.readTree(indicators).path("data");
            JsonNode summaryMetrics = json.readTree(summary).path("data").path("metrics");
            assertThat(metric(summaryMetrics, "PRODUCTION_CULTIVATED_AREA").path("value").asText())
                    .isEqualTo("10");
            for (String code : List.of(
                    "PRODUCTION_CULTIVATED_AREA", "PRODUCTION_ESTIMATED_OUTPUT",
                    "MARKET_AVERAGE_PURCHASE_PRICE", "MARKET_AVERAGE_SALE_PRICE")) {
                JsonNode indicator = metric(indicatorData, code);
                JsonNode headline = metric(summaryMetrics, code);
                assertThat(indicator.path("value")).isEqualTo(headline.path("value"));
                assertThat(indicator.path("sourceCount")).isEqualTo(headline.path("sourceCount"));
                assertThat(indicator.path("dataCutoff")).isEqualTo(headline.path("dataCutoff"));
                assertThat(indicator.path("coverageStatus")).isEqualTo(headline.path("coverageStatus"));
                assertThat(indicator.path("formula")).isEqualTo(headline.path("formula"));
                assertThat(indicator.path("sourcePath")).isEqualTo(headline.path("sourcePath"));
                assertThat(indicator.path("sourceRelation")).isEqualTo(headline.path("sourceRelation"));
                assertThat(indicator.path("coverageScope")).isEqualTo(headline.path("coverageScope"));
                assertThat(indicator.path("calculationVersion"))
                        .isEqualTo(headline.path("calculationVersion"));
            }
        } finally {
            jdbc.sql("""
                    UPDATE overview.administrative_boundary SET geometry_sha256=:hash
                    WHERE region_code='230208'
                    """).param("hash", originalBoundaryHash).update();
        }
    }

    private static JsonNode metric(JsonNode metrics, String code) {
        for (JsonNode metric : metrics) {
            if (code.equals(metric.path("code").asText())) return metric;
        }
        throw new AssertionError("Missing overview metric " + code);
    }

    @Test
    void rawLedgerKeepsRepeatedImportsWhileEveryMetricConsumerUsesOneLatestEffectiveSample()
            throws Exception {
        jdbc.sql("""
                INSERT INTO production.production_record_submission_metadata(record_id,field_code,value)
                VALUES(:approved,'PROD_SAMPLE_NAME','同一农户'),
                      (:approved,'PROD_SAMPLE_CONTACT','13800000000'),
                      (:approved,'PROD_SAMPLE_LATITUDE','47.1000000'),
                      (:approved,'PROD_SAMPLE_LONGITUDE','123.1000000')
                """).param("approved", APPROVED).update();
        jdbc.sql("""
                INSERT INTO production.production_record(
                  record_id,product_code,object_type_code,region_code,survey_date,reported_at,
                  cultivated_area_mu,yield_per_mu_kg,status_code,last_modified_by,version,
                  survey_year,survey_month,survey_period_precision,survey_period_governance_state,
                  sample_point_id)
                VALUES(:latest,'CORN','FARMER','230208',DATE '2026-08-10',
                  TIMESTAMPTZ '2026-08-10 12:34:56+08',30,40,'APPROVED',:actor,1,
                  2026,NULL,'YEAR','CONFIRMED',:samplePointId)
                """).param("latest", LATEST_APPROVED).param("actor", ACTOR)
                .param("samplePointId", FORMAL_SAMPLE_POINT).update();
        jdbc.sql("""
                INSERT INTO production.production_record_submission_metadata(record_id,field_code,value)
                VALUES(:latest,'PROD_SAMPLE_NAME','同一农户'),
                      (:latest,'PROD_SAMPLE_CONTACT','13800000000'),
                      (:latest,'PROD_SAMPLE_LATITUDE','47.1000000'),
                      (:latest,'PROD_SAMPLE_LONGITUDE','123.1000000')
                """).param("latest", LATEST_APPROVED).update();
        jdbc.sql("""
                INSERT INTO platform.business_event_outbox(
                  event_id,aggregate_type,aggregate_id,action_code,actor_subject_id,work_unit_code,
                  region_codes,product_code,occurred_at,detail)
                VALUES(CAST(:eventId AS uuid),'PRODUCTION_RECORD',:latest,'PRODUCTION_RECORD_APPROVED',
                  :actor,'TEST_UNIT',ARRAY['230208']::varchar(18)[],'CORN',
                  TIMESTAMPTZ '2026-08-10 14:00:00+08','{}'::jsonb)
                """).param("eventId", UUID.randomUUID().toString())
                .param("latest", LATEST_APPROVED).param("actor", ACTOR).update();

        mvc.perform(get("/api/v1/production-records").principal(() -> ACTOR)
                        .queryParam("productCode", "CORN").queryParam("pageKind", "MONITORING")
                        .queryParam("pageNumber", "0").queryParam("pageSize", "20")
                        .queryParam("filter.status", "APPROVED").queryParam("filter.regionCode", "230208"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(LATEST_APPROVED));
        mvc.perform(get("/api/v1/observable-analysis/snapshots").principal(() -> ACTOR)
                        .queryParam("productCode", "CORN").queryParam("regionCode", "230208")
                        .queryParam("surveyYear", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.production.metrics[?(@.code == 'CULTIVATED_AREA')].value")
                        .value(org.hamcrest.Matchers.hasItem("30.0000")))
                .andExpect(jsonPath("$.data.production.metrics[?(@.code == 'CULTIVATED_AREA')].sourceCount")
                        .value(org.hamcrest.Matchers.hasItem(1)))
                .andExpect(jsonPath("$.data.supply.calculation.expectedOutputTonnes").value("1.2000"));
        mvc.perform(get("/api/v1/overview/indicators").principal(() -> ACTOR)
                        .queryParam("productCode", "CORN").queryParam("regionCode", "230208")
                        .queryParam("year", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.code == 'PRODUCTION_CULTIVATED_AREA')].value")
                        .value(org.hamcrest.Matchers.hasItem("30")))
                .andExpect(jsonPath("$.data[?(@.code == 'PRODUCTION_CULTIVATED_AREA')].sourceCount")
                        .value(org.hamcrest.Matchers.hasItem(1)));
        mvc.perform(get("/api/v1/overview/dashboard").principal(() -> ACTOR)
                        .queryParam("productCode", "CORN").queryParam("regionCode", "230208")
                        .queryParam("year", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.metrics[?(@.code == 'PRODUCTION_CULTIVATED_AREA')].value")
                        .value(org.hamcrest.Matchers.hasItem("30")))
                .andExpect(jsonPath("$.data.metrics[?(@.code == 'PRODUCTION_CULTIVATED_AREA')].sourceCount")
                        .value(org.hamcrest.Matchers.hasItem(1)))
                .andExpect(jsonPath("$.data.metrics[?(@.code == 'PRODUCTION_CULTIVATED_AREA')].dataCutoff")
                        .value(org.hamcrest.Matchers.hasItem("2026年08月10日 14:00:00")))
                .andExpect(jsonPath("$.data.scope.approvedRecordCount").value(1))
                .andExpect(jsonPath("$.data.scope.latestUpdatedAt")
                        .value("2026年08月10日 14:00:00"));
    }

    @Test
    void approvedMarketInventoryUpdatesEveryConsumerWithoutWaitingForPendingRecords()
            throws Exception {
        String approved = createMarketInventory(
                "核定库存企业", "13900000001", "47.3500000", "123.9100000", "20", true);
        setMarketApprovalTime(approved, "2026-08-19 12:00:00+08");
        String pending = createMarketInventory(
                "待审核库存企业", "13900000002", "47.3600000", "123.9200000", "30", false);

        mvc.perform(get("/api/v1/market-records").principal(() -> ACTOR)
                        .queryParam("productCode", "CORN").queryParam("pageKind", "MONITORING")
                        .queryParam("pageNumber", "0").queryParam("pageSize", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].id").value(
                        org.hamcrest.Matchers.contains(approved)));
        mvc.perform(get("/api/v1/observable-analysis/snapshots").principal(() -> ACTOR)
                        .queryParam("productCode", "CORN").queryParam("regionCode", "230200")
                        .queryParam("surveyYear", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.coverage.pendingReviewRecordCount").value(1))
                .andExpect(jsonPath("$.data.supply.inventory.enterpriseEndingTonnes").value("20.0000"))
                .andExpect(jsonPath("$.data.supply.inventory.adoptedRecordCount").value(1))
                .andExpect(jsonPath("$.data.dataCutoffAt").value("2026-08-19T04:00:00Z"));
        assertOverviewExcludesSampleSupply("2026年08月19日 12:00:00");

        mvc.perform(post("/api/v1/market-records/{id}/approve", pending)
                        .principal(() -> ACTOR).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));
        setMarketApprovalTime(pending, "2026-08-19 13:00:00+08");

        mvc.perform(get("/api/v1/observable-analysis/snapshots").principal(() -> ACTOR)
                        .queryParam("productCode", "CORN").queryParam("regionCode", "230200")
                        .queryParam("surveyYear", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.coverage.pendingReviewRecordCount").value(0))
                .andExpect(jsonPath("$.data.supply.inventory.enterpriseEndingTonnes").value("50.0000"))
                .andExpect(jsonPath("$.data.supply.inventory.adoptedRecordCount").value(2))
                .andExpect(jsonPath("$.data.dataCutoffAt").value("2026-08-19T05:00:00Z"));
        assertOverviewExcludesSampleSupply("2026年08月19日 13:00:00");
    }

    private String createMarketInventory(
            String name, String contact, String latitude, String longitude,
            String inventoryTonnes, boolean approve) throws Exception {
        UUID photoId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO evidence.evidence_photo(
                  photo_id,state_code,original_filename,media_type,original_bytes,watermarked_bytes,
                  byte_length,sha256,captured_at,capture_latitude,capture_longitude,watermark_text,
                  uploaded_by,uploaded_at)
                VALUES(:id,'STAGED','inventory-fixture.png','image/png',decode('00','hex'),
                  decode('01','hex'),1,encode(sha256(decode('00','hex')),'hex'),now(),
                  CAST(:latitude AS numeric),CAST(:longitude AS numeric),'库存核定测试水印',
                  'market-tester',now())
                """).param("id", photoId).param("latitude", latitude).param("longitude", longitude)
                .update();
        String body = """
                {"productCode":"CORN","coreValues":{
                 "MKT_OBJECT_TYPE":"TRADER","MKT_REGION":"230200",
                 "MKT_TRADE_DATE":"2026-08-01",
                 "MKT_PURCHASE_BASE_PRICE":"2300","MKT_SALE_BASE_PRICE":"2350",
                 "MKT_CARRIAGE_BOARD_AMOUNT":"36","MKT_PACKAGING_AMOUNT":"12",
                 "MKT_FREIGHT_AMOUNT":"72","MKT_PACKAGING_FORM":"BULK",
                 "MKT_REPORTER_NAME":"市场测试员","MKT_SURVEYOR_NAME":"王雷",
                 "MKT_SURVEYOR_PHONE":"13800000000","MKT_SAMPLE_NAME":"%s",
                 "MKT_SAMPLE_CONTACT":"%s","MKT_SAMPLE_LATITUDE":"%s",
                 "MKT_SAMPLE_LONGITUDE":"%s"},
                 "facts":{"ENDING_INVENTORY":"%s"},"evidencePhotoIds":["%s"]}
                """.formatted(name, contact, latitude, longitude, inventoryTonnes, photoId);
        String id = mvc.perform(post("/api/v1/market-records").principal(() -> "market-tester")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()
                .replaceFirst("(?s).*?\\\"id\\\":\\\"([^\\\"]+)\\\".*", "$1");
        mvc.perform(post("/api/v1/market-records/{id}/submit", id)
                        .principal(() -> "market-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING_REVIEW"));
        if (approve) {
            mvc.perform(post("/api/v1/market-records/{id}/approve", id)
                            .principal(() -> ACTOR).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"version\":1}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("APPROVED"));
        }
        return id;
    }

    private void setMarketApprovalTime(String recordId, String approvedAt) {
        jdbc.sql("""
                UPDATE platform.business_event_outbox SET occurred_at=CAST(:approvedAt AS timestamptz)
                WHERE aggregate_type='MARKET_RECORD' AND aggregate_id=:recordId
                  AND action_code='MARKET_RECORD_APPROVED'
                """).param("approvedAt", approvedAt).param("recordId", recordId).update();
    }

    private void assertOverviewExcludesSampleSupply(String expectedCutoff) throws Exception {
        mvc.perform(get("/api/v1/overview/dashboard").principal(() -> ACTOR)
                        .queryParam("productCode", "CORN").queryParam("regionCode", "230200")
                        .queryParam("year", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.metrics[?(@.code =~ /SUPPLY_.*/)]").isEmpty())
                .andExpect(jsonPath("$.data.metrics[?(@.code == 'REGION_SURPLUS')]").isEmpty())
                .andExpect(jsonPath("$.data.scope.latestUpdatedAt").value(expectedCutoff));
    }
}
