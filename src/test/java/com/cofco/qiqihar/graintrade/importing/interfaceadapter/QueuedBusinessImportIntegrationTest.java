package com.cofco.qiqihar.graintrade.importing.interfaceadapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.importing.application.LogisticsImportTemplate;
import com.cofco.qiqihar.graintrade.importing.application.MarketImportTemplate;
import com.cofco.qiqihar.graintrade.importing.infrastructure.BusinessImportWorkbook;
import com.cofco.qiqihar.graintrade.logistics.importing.LogisticsImportDefinition;
import com.cofco.qiqihar.graintrade.logistics.importing.LogisticsImportPort;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = GrainTradeApplication.class, properties = {
    "qiqihar.import.sync-row-limit=1",
    "qiqihar.import.max-row-limit=100",
    "qiqihar.import.queue-enabled=true",
    "qiqihar.import.queue-poll-delay=50ms",
    "qiqihar.import.queue-stale-after=2m"
})
@AutoConfigureMockMvc
@UsesProtectedTestDatabase
@org.springframework.test.annotation.DirtiesContext(
        classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class QueuedBusinessImportIntegrationTest {
    private static final UUID FIRST_PHOTO = UUID.fromString("00000000-0000-0000-0000-000000000081");
    private static final UUID SECOND_PHOTO = UUID.fromString("00000000-0000-0000-0000-000000000082");

    @Autowired MockMvc mvc;
    @Autowired DataSource dataSource;
    @Autowired LogisticsImportPort logistics;
    private JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        jdbc = JdbcClient.create(dataSource);
        clean();
        insertPhoto(FIRST_PHOTO, "async-one.png", "production-tester");
        insertPhoto(SECOND_PHOTO, "async-two.png", "production-tester");
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    @Test
    void queuesLargeBatchesThenCompletesThemDurablyWithoutDuplicateWrites() throws Exception {
        String header = "productCode,objectTypeCode,regionCode,cultivarCode,surveyDate,cultivatedAreaMu,"
                + "yieldPerMuKilograms,PROD_REPORTER_NAME,PROD_SURVEYOR_NAME,PROD_SURVEYOR_PHONE,PROD_SAMPLE_CONTACT,"
                + "PROD_SAMPLE_LATITUDE,PROD_SAMPLE_LONGITUDE,evidencePhotoId\n";
        String rows = "CORN,FARMER,230200,,2026-08-09,10,500,伪造甲,王雷,13800000001,13900000001,"
                + "47.3543,123.9182," + FIRST_PHOTO + "\n"
                + "CORN,FARMER,230200,,2026-08-09,20,510,伪造乙,王雷,13800000002,13900000002,"
                + "47.3544,123.9183," + SECOND_PHOTO + "\n";
        MockMultipartFile file = new MockMultipartFile(
                "file", "large-production.csv", "text/csv", (header + rows).getBytes(StandardCharsets.UTF_8));

        String response = mvc.perform(multipart("/api/v1/imports/production")
                        .file(file).param("productCode", "CORN").param("objectTypeCode", "FARMER")
                        .header("Idempotency-Key", "queued-production-1")
                        .principal(() -> "production-tester"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.statusCode").value("QUEUED"))
                .andReturn().getResponse().getContentAsString();
        UUID jobId = UUID.fromString(response.replaceFirst("(?s).*?\"id\":\"([^\"]+)\".*", "$1"));

        assertThat(await("production", jobId, "production-tester")).isEqualTo("COMPLETED");
        assertThat(jdbc.sql("SELECT count(*) FROM production.production_record").query(Long.class).single())
                .isEqualTo(2L);
        assertThat(jdbc.sql("SELECT count(*) FROM platform.import_row_result WHERE import_job_id=:id")
                        .param("id", jobId).query(Long.class).single()).isEqualTo(2L);

        mvc.perform(multipart("/api/v1/imports/production")
                        .file(file).param("productCode", "CORN").param("objectTypeCode", "FARMER")
                        .header("Idempotency-Key", "queued-production-1")
                        .principal(() -> "production-tester"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(jobId.toString()))
                .andExpect(jsonPath("$.data.importedRows").value(2));
        assertThat(jdbc.sql("SELECT count(*) FROM production.production_record").query(Long.class).single())
                .isEqualTo(2L);
    }

    @Test
    void usesTheSameDurableQueueForMarketImports() throws Exception {
        UUID third = UUID.fromString("00000000-0000-0000-0000-000000000083");
        UUID fourth = UUID.fromString("00000000-0000-0000-0000-000000000084");
        insertPhoto(third, "market-one.png", "market-tester");
        insertPhoto(fourth, "market-two.png", "market-tester");
        String header = String.join(",", MarketImportTemplate.HEADERS) + "\n";
        String csv = header + marketRow(third) + marketRow(fourth);

        String body = mvc.perform(multipart("/api/v1/imports/market")
                        .file(new MockMultipartFile("file", "large-market.csv", "text/csv",
                                csv.getBytes(StandardCharsets.UTF_8)))
                        .param("productCode", "CORN").param("objectTypeCode", "FEED_MILL")
                        .header("Idempotency-Key", "queued-market-1").principal(() -> "market-tester"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.statusCode").value("QUEUED"))
                .andReturn().getResponse().getContentAsString();
        UUID jobId = id(body);

        assertThat(await("market", jobId, "market-tester")).isEqualTo("COMPLETED");
        assertThat(jdbc.sql("SELECT count(*) FROM market.market_record").query(Long.class).single()).isEqualTo(2L);
    }

    @Test
    void routesReturnedCorrectionsBeforeOrdinaryMarketImportsAndRejectsUnknownReservedSources()
            throws Exception {
        boundary();
        String first = returnedMarket("队列修正甲");
        String second = returnedMarket("队列修正乙");
        String third = returnedMarket("队列修正丙");
        byte[] workbook = mvc.perform(get(
                                "/api/v1/imports/market/returned-corrections/template")
                        .param("productCode", "CORN").principal(() -> "market-tester"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();

        String response = mvc.perform(multipart(
                                "/api/v1/imports/market/returned-corrections")
                        .file(new MockMultipartFile("file", "市场-玉米-退回记录修正表.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", workbook))
                        .param("productCode", "CORN")
                        .header("Idempotency-Key", "queued-returned-market-correction")
                        .principal(() -> "market-tester"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.statusCode").value("QUEUED"))
                .andReturn().getResponse().getContentAsString();
        UUID jobId = id(response);

        assertThat(awaitReturnedCorrection(jobId)).isEqualTo("COMPLETED");
        assertThat(jdbc.sql("""
                SELECT count(*)=3 AND bool_and(status_code='PENDING_REVIEW') AND bool_and(version=4)
                FROM market.market_record WHERE record_id IN (:first,:second,:third)
                """).param("first", first).param("second", second).param("third", third)
                .query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("""
                SELECT source_content LIKE 'MARKET-RETURNED-CORRECTION-V1:%'
                  AND started_at IS NOT NULL AND attempt_count=1
                FROM platform.import_job WHERE import_job_id=:id
                """).param("id", jobId).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM platform.business_import_draft")
                .query(Long.class).single()).isZero();

        long recordsBeforeUnknown = jdbc.sql("SELECT count(*) FROM market.market_record")
                .query(Long.class).single();
        UUID unknownJob = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO platform.import_job(import_job_id,domain_code,idempotency_key,content_sha256,
                  source_content,requested_by,work_unit_code,status_code,created_at,attempt_count)
                SELECT :id,'MARKET',:key,repeat('b',64),'MARKET-UNKNOWN-V1:payload',
                  subject_id,work_unit_code,'QUEUED',now(),0
                FROM platform.security_user WHERE subject_id='market-tester'
                """).param("id", unknownJob).param("key", "unknown-market-" + unknownJob).update();
        assertThat(await("market", unknownJob, "market-tester")).isEqualTo("FAILED");
        assertThat(jdbc.sql("""
                SELECT failure_code FROM platform.import_job WHERE import_job_id=:id
                """).param("id", unknownJob).query(String.class).single())
                .isEqualTo("INVALID_IMPORT_TEMPLATE");
        assertThat(jdbc.sql("SELECT count(*) FROM market.market_record").query(Long.class).single())
                .isEqualTo(recordsBeforeUnknown);
    }

    @Test
    void usesTheSameDurableQueueForLogisticsImports() throws Exception {
        node("ASYNC_ORIGIN", "异步始发点", "RAIL_NODE");
        node("ASYNC_DEST", "异步到达点", "ROAD_NODE");
        LogisticsImportDefinition definition = logistics.definition("CORN");
        var row = LogisticsImportTemplate.codes(definition).stream().map(this::logisticsValue).toList();
        byte[] workbook = BusinessImportWorkbook.create(LogisticsImportTemplate.workbook(definition),
                java.util.List.of(row, row));

        String body = mvc.perform(multipart("/api/v1/imports/logistics")
                        .file(new MockMultipartFile("file", "large-logistics.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", workbook))
                        .param("productCode", "CORN").header("Idempotency-Key", "queued-logistics-1")
                        .principal(() -> "logistics-tester"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.statusCode").value("QUEUED"))
                .andReturn().getResponse().getContentAsString();
        UUID jobId = id(body);

        assertThat(await("logistics", jobId, "logistics-tester")).isEqualTo("COMPLETED");
        assertThat(jdbc.sql("SELECT count(*) FROM logistics.route_event").query(Long.class).single()).isEqualTo(2L);
    }

    @Test
    void rollsBackEveryGovernedWorkbookWriteWhenAQueuedRowFails() throws Exception {
        node("ASYNC_ORIGIN", "异步始发点", "RAIL_NODE");
        node("ASYNC_DEST", "异步到达点", "ROAD_NODE");
        LogisticsImportDefinition definition = logistics.definition("CORN");
        var codes = LogisticsImportTemplate.codes(definition);
        var valid = codes.stream().map(this::logisticsValue).toList();
        var invalid = new java.util.ArrayList<>(valid);
        invalid.set(codes.indexOf("LOG_TRANSPORT_MODE"), "AIR");
        byte[] workbook = BusinessImportWorkbook.create(LogisticsImportTemplate.workbook(definition),
                java.util.List.of(valid, invalid));

        String body = mvc.perform(multipart("/api/v1/imports/logistics")
                        .file(new MockMultipartFile("file", "queued-logistics-atomic.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", workbook))
                        .param("productCode", "CORN").header("Idempotency-Key", "queued-logistics-atomic")
                        .principal(() -> "logistics-tester"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.statusCode").value("QUEUED"))
                .andReturn().getResponse().getContentAsString();
        UUID jobId = id(body);

        assertThat(await("logistics", jobId, "logistics-tester")).isEqualTo("COMPLETED_WITH_ERRORS");
        assertThat(jdbc.sql("SELECT count(*) FROM logistics.route_event").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM platform.business_import_draft").query(Long.class).single())
                .isZero();
        assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.import_row_result
                WHERE import_job_id=:id AND error_code='NOT_IMPORTED_ATOMIC_BATCH'
                """).param("id", jobId).query(Long.class).single()).isOne();
    }

    @Test
    void rejectsBatchesBeyondTheConfiguredMaximumWithoutCreatingAJob() throws Exception {
        String header = "productCode,objectTypeCode,regionCode,cultivarCode,surveyDate,cultivatedAreaMu,"
                + "yieldPerMuKilograms,PROD_REPORTER_NAME,PROD_SURVEYOR_NAME,PROD_SURVEYOR_PHONE,PROD_SAMPLE_CONTACT,"
                + "PROD_SAMPLE_LATITUDE,PROD_SAMPLE_LONGITUDE,evidencePhotoId\n";
        String row = "CORN,FARMER,230200,,2026-08-09,10,500,伪造姓名,王雷,13800000001,13900000001,"
                + "47.3543,123.9182," + FIRST_PHOTO + "\n";

        mvc.perform(multipart("/api/v1/imports/production")
                        .file(new MockMultipartFile("file", "too-large.csv", "text/csv",
                                (header + row.repeat(101)).getBytes(StandardCharsets.UTF_8)))
                        .param("productCode", "CORN").param("objectTypeCode", "FARMER")
                        .header("Idempotency-Key", "queued-production-too-large")
                        .principal(() -> "production-tester"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("IMPORT_ROW_LIMIT_EXCEEDED"));

        assertThat(jdbc.sql("SELECT count(*) FROM platform.import_job").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM production.production_record").query(Long.class).single()).isZero();
    }

    @Test
    void recoversAStaleProcessingJobAndPublishesASafeFailure() throws Exception {
        UUID jobId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO platform.import_job(import_job_id,domain_code,idempotency_key,content_sha256,
                  source_content,requested_by,work_unit_code,status_code,created_at,started_at,completed_at,
                  attempt_count,failure_code,failure_message,lease_token,lease_until)
                SELECT :id,'PRODUCTION','stale-job',repeat('a',64),'invalid queued source',subject_id,
                  work_unit_code,'PROCESSING',now()-interval '3 minutes',now()-interval '3 minutes',NULL,
                  0,NULL,NULL,:leaseToken,now()-interval '1 minute'
                FROM platform.security_user WHERE subject_id='production-tester'
                """).param("id", jobId).param("leaseToken", UUID.randomUUID()).update();

        assertThat(await("production", jobId, "production-tester")).isEqualTo("FAILED");
        Map<String, Object> state = jdbc.sql("""
                SELECT status_code,attempt_count,failure_code,failure_message
                FROM platform.import_job WHERE import_job_id=:id
                """).param("id", jobId).query().singleRow();
        assertThat(state.get("status_code")).isEqualTo("FAILED");
        assertThat(((Number) state.get("attempt_count")).intValue()).isEqualTo(1);
        assertThat(state.get("failure_code")).isEqualTo("INVALID_IMPORT_TEMPLATE");
        assertThat((String) state.get("failure_message")).doesNotContain("Exception", "SQL", "stack");
        assertThat(jdbc.sql("SELECT count(*) FROM production.production_record").query(Long.class).single()).isZero();

        String retryBody = mvc.perform(post("/api/v1/imports/production/{jobId}/retries", jobId)
                        .principal(() -> "production-tester"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.statusCode").value("QUEUED"))
                .andExpect(jsonPath("$.data.retryOf").value(jobId.toString()))
                .andReturn().getResponse().getContentAsString();
        assertThat(await("production", id(retryBody), "production-tester")).isEqualTo("FAILED");
    }

    private String await(String domain, UUID jobId, String subject) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        String statusCode = "QUEUED";
        while (Instant.now().isBefore(deadline) && !statusCode.startsWith("COMPLETED")
                && !statusCode.equals("FAILED")) {
            Thread.sleep(50);
            String body = mvc.perform(get("/api/v1/imports/{domain}/{jobId}", domain, jobId)
                            .principal(() -> subject))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            statusCode = body.replaceFirst("(?s).*?\"statusCode\":\"([^\"]+)\".*", "$1");
        }
        return statusCode;
    }

    private String awaitReturnedCorrection(UUID jobId) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        String statusCode = "QUEUED";
        while (Instant.now().isBefore(deadline) && !statusCode.startsWith("COMPLETED")
                && !statusCode.equals("FAILED")) {
            Thread.sleep(50);
            String body = mvc.perform(get(
                                    "/api/v1/imports/market/returned-corrections/{id}", jobId)
                            .principal(() -> "market-tester"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            statusCode = body.replaceFirst("(?s).*?\"statusCode\":\"([^\"]+)\".*", "$1");
        }
        return statusCode;
    }

    private static UUID id(String body) {
        return UUID.fromString(body.replaceFirst("(?s).*?\"id\":\"([^\"]+)\".*", "$1"));
    }

    private static String marketRow(UUID photoId) {
        return String.join(",", "CORN", "FEED_MILL", "230200", "2026-08-01", "2300", "",
                "36", "12", "72", "BULK", "伪造姓名", "13800000000", "齐齐哈尔粮店",
                "13900000000", "47.3543", "123.9182", "12", "14.6", photoId.toString()) + "\n";
    }

    private String logisticsValue(String code) {
        return switch (code) {
            case "surveyYear" -> "2026";
            case "surveyMonth" -> "8";
            case "LOG_SAMPLE_NAME" -> "齐齐哈尔物流中心";
            case "LOG_REGION" -> "230200";
            case "LOG_SURVEYOR_PHONE" -> "13800000000";
            case "LOG_SAMPLE_CONTACT" -> "13900000000";
            case "LOG_SAMPLE_LATITUDE" -> "47.354300";
            case "LOG_SAMPLE_LONGITUDE" -> "123.918200";
            case "LOG_TRANSPORT_MODE" -> "RAIL";
            case "LOG_DIRECTION" -> "INFLOW";
            case "LOG_ROUTE_VOLUME" -> "12.5000";
            case "LOG_FREIGHT_RATE" -> "80.2500";
            case "LOG_BOARD_PRICE" -> "2650.0000";
            default -> "";
        };
    }

    private void node(String code, String name, String type) {
        jdbc.sql("""
                INSERT INTO logistics.logistics_node(node_code,node_name,node_type_code,region_code)
                VALUES(:code,:name,:type,'230200')
                """).param("code", code).param("name", name).param("type", type).update();
    }

    private void insertPhoto(UUID id, String filename, String uploadedBy) {
        jdbc.sql("""
                INSERT INTO evidence.evidence_photo(photo_id,state_code,original_filename,media_type,
                  original_bytes,watermarked_bytes,byte_length,sha256,captured_at,capture_latitude,
                  capture_longitude,watermark_text,uploaded_by,uploaded_at)
                VALUES(:id,'STAGED',:filename,'image/png',decode('00','hex'),decode('01','hex'),1,
                  encode(sha256(decode('00','hex')),'hex'),now(),47.3543,123.9182,'测试水印',:uploadedBy,now())
                """).param("id", id).param("filename", filename).param("uploadedBy", uploadedBy)
                .update();
    }

    private String returnedMarket(String sampleName) throws Exception {
        UUID photo = UUID.randomUUID();
        insertPhoto(photo, sampleName + ".png", "market-tester");
        String body = """
                {"productCode":"CORN","coreValues":{
                 "MKT_OBJECT_TYPE":"TRADER","MKT_REGION":"230200","MKT_TRADE_DATE":"2026-08-01",
                 "MKT_PURCHASE_BASE_PRICE":"2300","MKT_SALE_BASE_PRICE":"2300",
                 "MKT_CARRIAGE_BOARD_AMOUNT":"36","MKT_PACKAGING_AMOUNT":"12",
                 "MKT_FREIGHT_AMOUNT":"72","MKT_PACKAGING_FORM":"BULK",
                 "MKT_SAMPLE_NAME":"%s","MKT_SURVEYOR_NAME":"王雷",
                 "MKT_SURVEYOR_PHONE":"13800000000","MKT_SAMPLE_CONTACT":"13900000000",
                 "MKT_SAMPLE_LATITUDE":"47.3543","MKT_SAMPLE_LONGITUDE":"123.9182"},
                 "facts":{"PURCHASE_VOLUME":"12","MOISTURE":"14.6"},
                 "evidencePhotoIds":["%s"]}
                """.formatted(sampleName, photo);
        String id = mvc.perform(post("/api/v1/market-records")
                        .principal(() -> "market-tester").contentType("application/json").content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()
                .replaceFirst("(?s).*?\"id\":\"([^\"]+)\".*", "$1");
        mvc.perform(post("/api/v1/market-records/{id}/submit", id)
                        .principal(() -> "market-tester").contentType("application/json")
                        .content("{\"version\":0}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/market-records/{id}/return", id)
                        .principal(() -> "production-tester").contentType("application/json")
                        .content("{\"version\":1,\"reason\":\"地区与经纬度不匹配\"}"))
                .andExpect(status().isOk());
        return id;
    }

    private void boundary() {
        jdbc.sql("""
                INSERT INTO overview.administrative_boundary(
                  region_code,geometry,source_name,source_url,source_revision,source_license,
                  source_feature_id,source_effective_on,geometry_sha256)
                VALUES('230200',ST_Multi(ST_MakeEnvelope(122,46,125,49,4326)),
                  '退回市场队列验证','urn:test:returned-market-queue','test-v1',
                  'Test fixture','230200',DATE '2026-08-19',repeat('7',64))
                ON CONFLICT(region_code) DO UPDATE SET geometry=EXCLUDED.geometry,
                  source_name=EXCLUDED.source_name,source_url=EXCLUDED.source_url,
                  source_revision=EXCLUDED.source_revision,source_license=EXCLUDED.source_license,
                  source_feature_id=EXCLUDED.source_feature_id,
                  source_effective_on=EXCLUDED.source_effective_on,
                  geometry_sha256=EXCLUDED.geometry_sha256
                """).update();
    }

    private void clean() {
        jdbc.sql("""
                TRUNCATE platform.import_row_result,platform.import_job,platform.business_audit_event,
                  production.production_record,market.market_record,logistics.route_event,
                  logistics.logistics_node,evidence.evidence_photo RESTART IDENTITY CASCADE
                """).update();
        jdbc.sql("DELETE FROM overview.administrative_boundary WHERE source_url=:source")
                .param("source", "urn:test:returned-market-queue").update();
    }
}
