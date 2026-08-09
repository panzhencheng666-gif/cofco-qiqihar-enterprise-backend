package com.cofco.qiqihar.graintrade.shared.security.interfaceadapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = GrainTradeApplication.class)
@AutoConfigureMockMvc
@UsesProtectedTestDatabase
class AuthenticatedReporterContractIntegrationTest {
    private static final String AUTHOR = "identity-contract-author";
    private static final String COLLEAGUE = "identity-contract-colleague";
    private static final String OUTSIDER = "identity-contract-outsider";
    private static final String REGION = "230202";
    private static final String OUTSIDE_REGION = "231102";

    @Autowired MockMvc mvc;
    @Autowired DataSource dataSource;
    private JdbcClient jdbc;

    @BeforeEach
    void provisionEmployees() {
        jdbc = JdbcClient.create(dataSource);
        cleanup();
        jdbc.sql("""
                INSERT INTO platform.work_unit(code,name,sort_order)
                VALUES ('IDENTITY_CONTRACT_HOME','身份契约测试单位',9970),
                       ('IDENTITY_CONTRACT_OUTSIDE','身份契约外部单位',9971)
                ON CONFLICT(code) DO NOTHING
                """).update();
        jdbc.sql("""
                INSERT INTO platform.work_unit_region_scope(work_unit_code,region_code)
                VALUES ('IDENTITY_CONTRACT_HOME',:region),
                       ('IDENTITY_CONTRACT_OUTSIDE',:outsideRegion)
                ON CONFLICT DO NOTHING
                """).param("region", REGION).param("outsideRegion", OUTSIDE_REGION).update();
        jdbc.sql("""
                INSERT INTO platform.security_user(subject_id,display_name,work_unit_code)
                VALUES (:author,'王洋','IDENTITY_CONTRACT_HOME'),
                       (:colleague,'李敏','IDENTITY_CONTRACT_HOME'),
                       (:outsider,'赵强','IDENTITY_CONTRACT_OUTSIDE')
                """).param("author", AUTHOR).param("colleague", COLLEAGUE)
                .param("outsider", OUTSIDER).update();
        jdbc.sql("""
                INSERT INTO platform.security_user_role(subject_id,role_code)
                VALUES (:author,'SYSTEM_ADMIN'),(:colleague,'SYSTEM_ADMIN'),(:outsider,'SYSTEM_ADMIN')
                """).param("author", AUTHOR).param("colleague", COLLEAGUE)
                .param("outsider", OUTSIDER).update();
        jdbc.sql("""
                INSERT INTO platform.security_user_region_scope(subject_id,region_code)
                VALUES (:author,:region),(:colleague,:region),(:outsider,:outsideRegion)
                """).param("author", AUTHOR).param("colleague", COLLEAGUE)
                .param("outsider", OUTSIDER).param("region", REGION)
                .param("outsideRegion", OUTSIDE_REGION).update();
    }

    @AfterEach
    void removeEmployees() {
        cleanup();
    }

    @Test
    void sessionReturnsOnlyAuthenticatedEmployeeProfileAndAuthorizationScope() throws Exception {
        mvc.perform(get("/api/v1/session/me").principal(() -> AUTHOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.subjectId").value(AUTHOR))
                .andExpect(jsonPath("$.data.displayName").value("王洋"))
                .andExpect(jsonPath("$.data.workUnitCode").value("IDENTITY_CONTRACT_HOME"))
                .andExpect(jsonPath("$.data.permissions", hasItem("BUSINESS_CREATE")))
                .andExpect(jsonPath("$.data.regionCodes.length()").value(1))
                .andExpect(jsonPath("$.data.regionCodes[0]").value(REGION))
                .andExpect(jsonPath("$.data.enabled").doesNotExist());

        mvc.perform(get("/api/v1/session/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void productionCreateAndUpdateIgnoreForgedReporterAndPreserveOriginalEmployee() throws Exception {
        UUID photoId = stagePhoto(AUTHOR, "production.png");
        String created = mvc.perform(post("/api/v1/production-records")
                        .principal(() -> AUTHOR).contentType(MediaType.APPLICATION_JSON)
                        .content(productionDraft("伪造创建人", photoId, null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.submissionMetadata.PROD_REPORTER_NAME").value("王洋"))
                .andReturn().getResponse().getContentAsString();
        String id = id(created);

        mvc.perform(put("/api/v1/production-records/{id}", id)
                        .principal(() -> COLLEAGUE).contentType(MediaType.APPLICATION_JSON)
                        .content(productionDraft("伪造修改人", photoId, 0L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.submissionMetadata.PROD_REPORTER_NAME").value("王洋"));

        mvc.perform(get("/api/v1/production-records/{id}", id).principal(() -> COLLEAGUE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.submissionMetadata.PROD_REPORTER_NAME").value("王洋"));
        mvc.perform(get("/api/v1/production-records/{id}", id).principal(() -> OUTSIDER))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_REGION_DENIED"));
    }

    @Test
    void marketCreateAndUpdateIgnoreForgedReporterAndPreserveOriginalEmployee() throws Exception {
        UUID photoId = stagePhoto(AUTHOR, "market.png");
        String created = mvc.perform(post("/api/v1/market-records")
                        .principal(() -> AUTHOR).contentType(MediaType.APPLICATION_JSON)
                        .content(marketDraft("伪造创建人", photoId, null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.coreValues.MKT_REPORTER_NAME").value("王洋"))
                .andReturn().getResponse().getContentAsString();
        String id = id(created);

        mvc.perform(put("/api/v1/market-records/{id}", id)
                        .principal(() -> COLLEAGUE).contentType(MediaType.APPLICATION_JSON)
                        .content(marketDraft("伪造修改人", photoId, 0L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.coreValues.MKT_REPORTER_NAME").value("王洋"));
    }

    @Test
    void committedBusinessChangeCreatesRegionScopedPersistentNotification() throws Exception {
        UUID photoId = stagePhoto(AUTHOR, "notification.png");
        String created = mvc.perform(post("/api/v1/production-records")
                        .principal(() -> AUTHOR).contentType(MediaType.APPLICATION_JSON)
                        .content(productionDraft("伪造创建人", photoId, null)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String recordId = id(created);

        String notificationId = mvc.perform(get("/api/v1/notifications")
                        .principal(() -> COLLEAGUE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unreadCount").value(1))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].aggregateType").value("PRODUCTION_RECORD"))
                .andExpect(jsonPath("$.data.items[0].aggregateId").value(recordId))
                .andExpect(jsonPath("$.data.items[0].actionCode").value("PRODUCTION_RECORD_CREATED"))
                .andExpect(jsonPath("$.data.items[0].productCode").value("CORN"))
                .andExpect(jsonPath("$.data.items[0].regionCodes[0]").value(REGION))
                .andExpect(jsonPath("$.data.items[0].read").value(false))
                .andReturn().getResponse().getContentAsString()
                .replaceFirst("(?s).*?\\\"id\\\":\\\"([^\\\"]+)\\\".*", "$1");

        mvc.perform(get("/api/v1/notifications").principal(() -> OUTSIDER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unreadCount").value(0))
                .andExpect(jsonPath("$.data.items").isEmpty());

        mvc.perform(post("/api/v1/notifications/{id}/read", notificationId)
                        .principal(() -> COLLEAGUE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(notificationId))
                .andExpect(jsonPath("$.data.read").value(true));
        mvc.perform(get("/api/v1/notifications").principal(() -> COLLEAGUE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unreadCount").value(0))
                .andExpect(jsonPath("$.data.items[0].read").value(true));
    }

    @Test
    void productionImportOverwritesForgedReporterWithAuthenticatedEmployee() throws Exception {
        UUID photoId = stagePhoto(AUTHOR, "import.png");
        String csv = """
                productCode,objectTypeCode,regionCode,cultivarCode,surveyDate,cultivatedAreaMu,yieldPerMuKilograms,PROD_REPORTER_NAME,PROD_REPORTER_PHONE,PROD_SAMPLE_CONTACT,PROD_SAMPLE_LATITUDE,PROD_SAMPLE_LONGITUDE,evidencePhotoId
                CORN,FARMER,230202,,2026-08-01,10,20,伪造导入人,13800000000,13900000000,47.3543,123.9182,%s
                """.formatted(photoId);

        mvc.perform(multipart("/api/v1/imports/production")
                        .file(new MockMultipartFile("file", "identity.csv", "text/csv",
                                csv.getBytes(StandardCharsets.UTF_8)))
                        .param("productCode", "CORN").param("objectTypeCode", "FARMER")
                        .header("Idempotency-Key", "identity-contract-import")
                        .principal(() -> AUTHOR))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.importedRows").value(1));

        assertThat(jdbc.sql("""
                SELECT metadata.value
                FROM production.production_record record
                JOIN production.production_record_submission_metadata metadata
                  ON metadata.record_id = record.record_id
                 AND metadata.field_code = 'PROD_REPORTER_NAME'
                WHERE record.last_modified_by = :author
                """).param("author", AUTHOR).query(String.class).single()).isEqualTo("王洋");
    }

    private UUID stagePhoto(String subjectId, String filename) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO evidence.evidence_photo(photo_id,state_code,original_filename,media_type,
                  original_bytes,watermarked_bytes,byte_length,sha256,captured_at,capture_latitude,
                  capture_longitude,watermark_text,uploaded_by,uploaded_at)
                VALUES(:id,'STAGED',:filename,'image/png',decode('00','hex'),decode('01','hex'),
                  1,repeat('d',64),now(),47.3543,123.9182,'身份契约水印',:subjectId,now())
                """).param("id", id).param("filename", filename).param("subjectId", subjectId).update();
        return id;
    }

    private static String productionDraft(String forgedReporter, UUID photoId, Long version) {
        String versionValue = version == null ? "" : ",\"version\":" + version;
        return """
                {"productCode":"CORN","objectTypeCode":"FARMER","regionCode":"230202",
                 "surveyDate":"2026-08-01","cultivatedAreaMu":"10","yieldPerMuKilograms":"20",
                 "quality":{},"costs":{},"insurance":{},"subsidies":{},
                 "submissionMetadata":{"PROD_REPORTER_NAME":"%s","PROD_REPORTER_PHONE":"13800000000",
                 "PROD_SAMPLE_CONTACT":"13900000000","PROD_SAMPLE_LATITUDE":"47.3543",
                 "PROD_SAMPLE_LONGITUDE":"123.9182"},"evidencePhotoIds":["%s"]%s}
                """.formatted(forgedReporter, photoId, versionValue);
    }

    private static String marketDraft(String forgedReporter, UUID photoId, Long version) {
        String versionValue = version == null ? "" : ",\"version\":" + version;
        return """
                {"productCode":"CORN","coreValues":{
                 "MKT_OBJECT_TYPE":"FEED_MILL","MKT_REGION":"230202",
                 "MKT_TRADE_DATE":"2026-08-01",
                 "MKT_PURCHASE_BASE_PRICE":"2300","MKT_SALE_BASE_PRICE":"2300",
                 "MKT_CARRIAGE_BOARD_AMOUNT":"36","MKT_PACKAGING_AMOUNT":"12",
                 "MKT_FREIGHT_AMOUNT":"72","MKT_PACKAGING_FORM":"BULK",
                 "MKT_REPORTER_NAME":"%s","MKT_REPORTER_PHONE":"13800000000",
                 "MKT_SAMPLE_NAME":"齐齐哈尔第一粮店","MKT_SAMPLE_CONTACT":"13900000000",
                 "MKT_SAMPLE_LATITUDE":"47.3543","MKT_SAMPLE_LONGITUDE":"123.9182"},
                 "facts":{"PURCHASE_VOLUME":"12","MOISTURE":"14.6"},
                 "evidencePhotoIds":["%s"]%s}
                """.formatted(forgedReporter, photoId, versionValue);
    }

    private static String id(String response) {
        return response.replaceFirst("(?s).*?\\\"id\\\":\\\"([^\\\"]+)\\\".*", "$1");
    }

    private void cleanup() {
        if (jdbc == null) return;
        jdbc.sql("TRUNCATE platform.notification_read_receipt, platform.business_event_outbox").update();
        jdbc.sql("TRUNCATE platform.business_audit_event").update();
        jdbc.sql("DELETE FROM platform.import_row_result WHERE import_job_id IN (SELECT import_job_id FROM platform.import_job WHERE requested_by IN (:author,:colleague,:outsider))")
                .param("author", AUTHOR).param("colleague", COLLEAGUE).param("outsider", OUTSIDER).update();
        jdbc.sql("DELETE FROM platform.import_job WHERE requested_by IN (:author,:colleague,:outsider)")
                .param("author", AUTHOR).param("colleague", COLLEAGUE).param("outsider", OUTSIDER).update();
        jdbc.sql("DELETE FROM production.production_record WHERE last_modified_by IN (:author,:colleague,:outsider)")
                .param("author", AUTHOR).param("colleague", COLLEAGUE).param("outsider", OUTSIDER).update();
        jdbc.sql("DELETE FROM market.market_record WHERE last_modified_by IN (:author,:colleague,:outsider)")
                .param("author", AUTHOR).param("colleague", COLLEAGUE).param("outsider", OUTSIDER).update();
        jdbc.sql("DELETE FROM evidence.evidence_photo WHERE uploaded_by IN (:author,:colleague,:outsider)")
                .param("author", AUTHOR).param("colleague", COLLEAGUE).param("outsider", OUTSIDER).update();
        jdbc.sql("DELETE FROM platform.security_user_region_scope WHERE subject_id IN (:author,:colleague,:outsider)")
                .param("author", AUTHOR).param("colleague", COLLEAGUE).param("outsider", OUTSIDER).update();
        jdbc.sql("DELETE FROM platform.security_user_role WHERE subject_id IN (:author,:colleague,:outsider)")
                .param("author", AUTHOR).param("colleague", COLLEAGUE).param("outsider", OUTSIDER).update();
        jdbc.sql("DELETE FROM platform.security_user WHERE subject_id IN (:author,:colleague,:outsider)")
                .param("author", AUTHOR).param("colleague", COLLEAGUE).param("outsider", OUTSIDER).update();
        jdbc.sql("DELETE FROM platform.work_unit_region_scope WHERE work_unit_code IN ('IDENTITY_CONTRACT_HOME','IDENTITY_CONTRACT_OUTSIDE')").update();
        jdbc.sql("DELETE FROM platform.work_unit WHERE code IN ('IDENTITY_CONTRACT_HOME','IDENTITY_CONTRACT_OUTSIDE')").update();
    }
}
