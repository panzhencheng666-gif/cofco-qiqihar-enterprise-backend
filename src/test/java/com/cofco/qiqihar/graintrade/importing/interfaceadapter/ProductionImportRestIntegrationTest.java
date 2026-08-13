package com.cofco.qiqihar.graintrade.importing.interfaceadapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import com.cofco.qiqihar.graintrade.importing.application.ProductionImportTemplate;
import com.cofco.qiqihar.graintrade.importing.infrastructure.BusinessImportWorkbook;
import com.cofco.qiqihar.graintrade.importing.infrastructure.XlsxTable;

@SpringBootTest(classes = GrainTradeApplication.class)
@AutoConfigureMockMvc
@UsesProtectedTestDatabase
class ProductionImportRestIntegrationTest {
    private static final String PHOTO_ID = "00000000-0000-0000-0000-000000000011";
    @Autowired MockMvc mvc;
    @Autowired DataSource dataSource;
    JdbcClient jdbc;

    @BeforeEach
    void clean() {
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("""
                TRUNCATE platform.import_row_result,platform.import_job,platform.business_audit_event,
                  production.production_record,evidence.evidence_photo RESTART IDENTITY CASCADE
                """).update();
        jdbc.sql("""
                INSERT INTO evidence.evidence_photo(photo_id,state_code,original_filename,media_type,
                  original_bytes,watermarked_bytes,byte_length,sha256,captured_at,capture_latitude,
                  capture_longitude,watermark_text,uploaded_by,uploaded_at)
                VALUES(CAST(:id AS uuid),'STAGED','fixture.png','image/png',decode('00','hex'),decode('01','hex'),
                  1,encode(sha256(decode('00','hex')),'hex'),now(),47.3543,123.9182,'测试水印','production-tester',now())
                """).param("id", PHOTO_ID).update();
        jdbc.sql("""
                INSERT INTO platform.work_unit(code,name,sort_order) VALUES('IMPORT_LIMITED','导入受限测试单位',9901)
                ON CONFLICT(code) DO NOTHING
                """).update();
        jdbc.sql("""
                INSERT INTO platform.work_unit_region_scope(work_unit_code,region_code) VALUES('IMPORT_LIMITED','230200')
                ON CONFLICT DO NOTHING
                """).update();
        jdbc.sql("""
                INSERT INTO platform.security_user(subject_id,display_name,work_unit_code)
                VALUES('limited-importer','受限导入员','IMPORT_LIMITED') ON CONFLICT(subject_id) DO NOTHING
                """).update();
        jdbc.sql("""
                INSERT INTO platform.security_user_role(subject_id,role_code) VALUES('limited-importer','BUSINESS_OPERATOR')
                ON CONFLICT DO NOTHING
                """).update();
        jdbc.sql("""
                INSERT INTO platform.security_user_region_scope(subject_id,region_code) VALUES('limited-importer','230200')
                ON CONFLICT DO NOTHING
                """).update();
    }

    @AfterEach
    void cleanAfterEach() {
        jdbc.sql("""
                TRUNCATE platform.import_row_result,platform.import_job,platform.business_audit_event,
                  production.production_record,evidence.evidence_photo RESTART IDENTITY CASCADE
                """).update();
    }

    @Test
    void downloadsAProductSpecificXlsxTemplateWithoutAReporterInput() throws Exception {
        byte[] workbook = mvc.perform(get("/api/v1/imports/production/template")
                        .param("format", "xlsx")
                        .param("productCode", "CORN")
                        .param("objectTypeCode", "FARMER")
                        .principal(() -> "production-tester"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andReturn().getResponse().getContentAsByteArray();

        var template = template(workbook, "产情", "CORN", "FARMER");
        assertThat(XlsxTable.parseWorksheet(workbook, 1, template.headers().size()))
                .containsExactly(template.labels(), template.headers());
        assertThat(template.headers())
                .contains("PROD_CULTIVAR_NAME", "PROD_OPENING_INVENTORY", "PROD_ENDING_INVENTORY",
                        "PROD_SURPLUS_SUBJECT_CODE", "PROD_SURPLUS_CUTOFF_DATE", "MOISTURE", "TOXIN")
                .doesNotContain("cultivarCode")
                .doesNotContain("PROD_REPORTER_NAME", "PROTEIN", "OIL_YIELD",
                        "MILLING_YIELD", "BROWN_RICE_YIELD");
        assertThat(template.labels().getFirst()).isEqualTo("所在地区");
        assertThat(template.labels())
                .contains("预计收获面积（亩）", "水分（%）", "地租（元/亩）")
                .doesNotContain("所在地区代码");
    }

    @Test
    void importsTheDownloadedXlsxProtocolWithSurveyDetailsAndServerOwnedReporter() throws Exception {
        byte[] downloaded = mvc.perform(get("/api/v1/imports/production/template")
                        .param("format", "xlsx")
                        .param("productCode", "CORN")
                        .param("objectTypeCode", "FARMER")
                        .principal(() -> "production-tester"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        var template = template(downloaded, "产情", "CORN", "FARMER");
        java.util.ArrayList<String> row = new java.util.ArrayList<>(
                java.util.Collections.nCopies(template.headers().size(), ""));
        put(row, template.headers(), "regionCode",
                "齐齐哈尔市 / 梅里斯达斡尔族区");
        put(row, template.headers(), "PROD_CULTIVAR_NAME", "龙单86");
        put(row, template.headers(), "surveyDate", "2026-08-09");
        put(row, template.headers(), "cultivatedAreaMu", "100");
        put(row, template.headers(), "yieldPerMuKilograms", "500");
        put(row, template.headers(), "PROD_REPORTER_PHONE", "13800000000");
        put(row, template.headers(), "PROD_SAMPLE_CONTACT", "13900000000");
        put(row, template.headers(), "PROD_SAMPLE_LATITUDE", "47.3543");
        put(row, template.headers(), "PROD_SAMPLE_LONGITUDE", "123.9182");
        put(row, template.headers(), "PROD_SAMPLE_NAME", "龙江县第一调查户");
        put(row, template.headers(), "PROD_HARVEST_AREA_MU", "96.5");
        put(row, template.headers(), "PROD_GROWTH_STAGE", "灌浆期");
        put(row, template.headers(), "PROD_ENDING_INVENTORY", "12");
        put(row, template.headers(), "PROD_SURPLUS_SUBJECT_CODE", "farmer-longjiang-xlsx-1");
        put(row, template.headers(), "PROD_SURPLUS_CUTOFF_DATE", "2026-08-09");
        put(row, template.headers(), "MOISTURE", "14.2");
        put(row, template.headers(), "evidencePhotoId", PHOTO_ID);
        byte[] workbook = BusinessImportWorkbook.create(
                template, java.util.List.of(row));

        mvc.perform(multipart("/api/v1/imports/production")
                        .file(new MockMultipartFile("file", "corn-production.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", workbook))
                        .param("productCode", "CORN")
                        .param("objectTypeCode", "FARMER")
                        .header("Idempotency-Key", "corn-production-xlsx")
                        .principal(() -> "production-tester"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.statusCode").value("COMPLETED"))
                .andExpect(jsonPath("$.data.importedRows").value(1));

        mvc.perform(get("/api/v1/production-records")
                        .queryParam("productCode", "CORN").queryParam("pageKind", "MONITORING")
                        .queryParam("pageNumber", "0").queryParam("pageSize", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].values.PROD_CULTIVAR").value("龙单86"))
                .andExpect(jsonPath("$.data.items[0].values.PROD_SAMPLE_NAME").value("龙江县第一调查户"))
                .andExpect(jsonPath("$.data.items[0].values.PROD_HARVEST_AREA_MU").value("96.5"))
                .andExpect(jsonPath("$.data.items[0].values.PROD_GROWTH_STAGE").value("灌浆期"))
                .andExpect(jsonPath("$.data.items[0].values.PROD_SURPLUS_SUBJECT_CODE")
                        .value("farmer-longjiang-xlsx-1"))
                .andExpect(jsonPath("$.data.items[0].values.MOISTURE").value("14.2000"))
                .andExpect(jsonPath("$.data.items[0].values.PROD_REPORTER_NAME").value("产情测试员"));

        assertThat(jdbc.sql("SELECT region_code FROM production.production_record")
                .query(String.class).single()).isEqualTo("230208");
    }

    @Test
    void reportsMissingConditionalSurplusFieldsAsARowErrorInsteadOfServerFailure() throws Exception {
        byte[] downloaded = mvc.perform(get("/api/v1/imports/production/template")
                        .param("format", "xlsx")
                        .param("productCode", "CORN")
                        .param("objectTypeCode", "FARMER")
                        .principal(() -> "production-tester"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        var template = template(downloaded, "产情", "CORN", "FARMER");
        java.util.ArrayList<String> row = new java.util.ArrayList<>(
                java.util.Collections.nCopies(template.headers().size(), ""));
        put(row, template.headers(), "regionCode", "230208");
        put(row, template.headers(), "PROD_CULTIVAR_NAME", "条件字段失败样例");
        put(row, template.headers(), "surveyDate", "2026-08-09");
        put(row, template.headers(), "cultivatedAreaMu", "100");
        put(row, template.headers(), "yieldPerMuKilograms", "500");
        put(row, template.headers(), "PROD_REPORTER_PHONE", "13800000000");
        put(row, template.headers(), "PROD_SAMPLE_CONTACT", "13900000000");
        put(row, template.headers(), "PROD_SAMPLE_LATITUDE", "47.3543");
        put(row, template.headers(), "PROD_SAMPLE_LONGITUDE", "123.9182");
        put(row, template.headers(), "PROD_ENDING_INVENTORY", "12");
        put(row, template.headers(), "evidencePhotoId", PHOTO_ID);
        byte[] workbook = BusinessImportWorkbook.create(template, java.util.List.of(row));

        String response = mvc.perform(multipart("/api/v1/imports/production")
                        .file(new MockMultipartFile("file", "missing-surplus-contract.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", workbook))
                        .param("productCode", "CORN")
                        .param("objectTypeCode", "FARMER")
                        .header("Idempotency-Key", "missing-surplus-contract")
                        .principal(() -> "production-tester"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.statusCode").value("COMPLETED_WITH_ERRORS"))
                .andExpect(jsonPath("$.data.importedRows").value(0))
                .andExpect(jsonPath("$.data.failedRows").value(1))
                .andReturn().getResponse().getContentAsString();
        String jobId = response.replaceFirst("(?s).*?\"id\":\"([^\"]+)\".*", "$1");

        mvc.perform(get("/api/v1/imports/production/{jobId}/errors", jobId)
                        .principal(() -> "production-tester"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("INVALID_PRODUCTION_RECORD")));

        assertThat(jdbc.sql("SELECT count(*) FROM production.production_record")
                .query(Long.class).single()).isZero();
    }

    @Test
    void reportsUnavailableEvidenceAsARowErrorInsteadOfServerFailure() throws Exception {
        byte[] downloaded = mvc.perform(get("/api/v1/imports/production/template")
                        .param("format", "xlsx")
                        .param("productCode", "CORN")
                        .param("objectTypeCode", "FARMER")
                        .principal(() -> "production-tester"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        var template = template(downloaded, "产情", "CORN", "FARMER");
        java.util.ArrayList<String> row = new java.util.ArrayList<>(
                java.util.Collections.nCopies(template.headers().size(), ""));
        put(row, template.headers(), "regionCode", "230208");
        put(row, template.headers(), "PROD_CULTIVAR_NAME", "照片失败样例");
        put(row, template.headers(), "surveyDate", "2026-08-09");
        put(row, template.headers(), "cultivatedAreaMu", "100");
        put(row, template.headers(), "yieldPerMuKilograms", "500");
        put(row, template.headers(), "PROD_REPORTER_PHONE", "13800000000");
        put(row, template.headers(), "PROD_SAMPLE_CONTACT", "13900000000");
        put(row, template.headers(), "PROD_SAMPLE_LATITUDE", "47.3543");
        put(row, template.headers(), "PROD_SAMPLE_LONGITUDE", "123.9182");
        put(row, template.headers(), "evidencePhotoId", "00000000-0000-0000-0000-000000000099");
        byte[] workbook = BusinessImportWorkbook.create(template, java.util.List.of(row));

        String response = mvc.perform(multipart("/api/v1/imports/production")
                        .file(new MockMultipartFile("file", "missing-evidence.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", workbook))
                        .param("productCode", "CORN")
                        .param("objectTypeCode", "FARMER")
                        .header("Idempotency-Key", "missing-evidence")
                        .principal(() -> "production-tester"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.statusCode").value("COMPLETED_WITH_ERRORS"))
                .andExpect(jsonPath("$.data.importedRows").value(0))
                .andExpect(jsonPath("$.data.failedRows").value(1))
                .andReturn().getResponse().getContentAsString();
        String jobId = response.replaceFirst("(?s).*?\"id\":\"([^\"]+)\".*", "$1");

        mvc.perform(get("/api/v1/imports/production/{jobId}/errors", jobId)
                        .principal(() -> "production-tester"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("EVIDENCE_PHOTO_NOT_FOUND")));

        assertThat(jdbc.sql("SELECT count(*) FROM production.production_record")
                .query(Long.class).single()).isZero();
    }

    @Test
    void rejectsASoybeanWorkbookFromTheCornMenuBeforeAnyDurableEffect() throws Exception {
        byte[] downloaded = mvc.perform(get("/api/v1/imports/production/template")
                        .param("format", "xlsx")
                        .param("productCode", "SOYBEAN")
                        .param("objectTypeCode", "FARMER")
                        .principal(() -> "production-tester"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        var soybean = template(downloaded, "产情", "SOYBEAN", "FARMER");
        byte[] workbook = BusinessImportWorkbook.create(soybean, java.util.List.of(
                java.util.Collections.nCopies(soybean.headers().size(), "")));

        mvc.perform(multipart("/api/v1/imports/production")
                        .file(new MockMultipartFile("file", "soybean-production.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", workbook))
                        .param("productCode", "CORN")
                        .param("objectTypeCode", "FARMER")
                        .header("Idempotency-Key", "production-context-mismatch")
                        .principal(() -> "production-tester"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("IMPORT_CONTEXT_MISMATCH"));

        assertThat(jdbc.sql("SELECT count(*) FROM production.production_record").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM platform.import_job").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM platform.import_row_result").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM platform.business_audit_event").query(Long.class).single()).isZero();
    }

    private static void put(java.util.List<String> row, java.util.List<String> headers,
            String header, String value) {
        row.set(headers.indexOf(header), value);
    }

    private static BusinessImportWorkbook.Template template(byte[] workbook, String label,
            String productCode, String objectTypeCode) {
        var rows = XlsxTable.parseWorksheet(workbook, 1, 256);
        java.util.List<String> labels = withoutTrailingBlanks(rows.get(0));
        java.util.List<String> headers = withoutTrailingBlanks(rows.get(1));
        assertThat(headers).hasSameSizeAs(labels);
        return new BusinessImportWorkbook.Template("PRODUCTION", label, productCode, objectTypeCode,
                headers, labels);
    }

    private static java.util.List<String> withoutTrailingBlanks(java.util.List<String> values) {
        int size = values.size();
        while (size > 0 && values.get(size - 1).isBlank()) size--;
        return java.util.List.copyOf(values.subList(0, size));
    }

    @Test
    void rejectsPathologicalAndOverPrecisionImportDecimalsWithoutProductionWrites() throws Exception {
        String csv = """
                productCode,objectTypeCode,regionCode,cultivarCode,surveyDate,cultivatedAreaMu,yieldPerMuKilograms,PROD_REPORTER_NAME,PROD_REPORTER_PHONE,PROD_SAMPLE_CONTACT,PROD_SAMPLE_LATITUDE,PROD_SAMPLE_LONGITUDE,evidencePhotoId
                CORN,FARMER,230200,,2026-07-31,1E999999999,20,导入填报员,13800000000,13900000000,47.3543,123.9182,00000000-0000-0000-0000-000000000021
                CORN,FARMER,230200,,2026-07-31,100000000000000.0000,20,导入填报员,13800000000,13900000000,47.3543,123.9182,00000000-0000-0000-0000-000000000022
                """;

        mvc.perform(multipart("/api/v1/imports/production")
                        .file(new MockMultipartFile("file", "hostile-decimals.csv", "text/csv",
                                csv.getBytes(StandardCharsets.UTF_8)))
                        .param("productCode", "CORN")
                        .param("objectTypeCode", "FARMER")
                        .header("Idempotency-Key", "hostile-decimals")
                        .principal(() -> "production-tester"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.statusCode").value("COMPLETED_WITH_ERRORS"))
                .andExpect(jsonPath("$.data.importedRows").value(0))
                .andExpect(jsonPath("$.data.failedRows").value(2));

        assertThat(jdbc.sql("SELECT count(*) FROM production.production_record").query(Long.class).single()).isZero();
    }

    @Test
    void validatesRowsDownloadsErrorsAndRetriesWithoutDuplicatingTheOriginalImport() throws Exception {
        mvc.perform(get("/api/v1/imports/production/template").principal(() -> "production-tester"))
                .andExpect(status().isOk()).andExpect(content().string(
                        "productCode,objectTypeCode,regionCode,cultivarCode,surveyDate,cultivatedAreaMu,yieldPerMuKilograms,PROD_REPORTER_NAME,PROD_REPORTER_PHONE,PROD_SAMPLE_CONTACT,PROD_SAMPLE_LATITUDE,PROD_SAMPLE_LONGITUDE,evidencePhotoId\n"));

        String csv = """
                productCode,objectTypeCode,regionCode,cultivarCode,surveyDate,cultivatedAreaMu,yieldPerMuKilograms,PROD_REPORTER_NAME,PROD_REPORTER_PHONE,PROD_SAMPLE_CONTACT,PROD_SAMPLE_LATITUDE,PROD_SAMPLE_LONGITUDE,evidencePhotoId
                CORN,FARMER,230200,,2026-07-31,10.5,20,导入填报员,13800000000,13900000000,47.3543,123.9182,%s
                CORN,FARMER,230200,,bad-date,5,30,导入填报员,13800000000,13900000000,47.3543,123.9182,00000000-0000-0000-0000-000000000023
                """.formatted(PHOTO_ID);
        mvc.perform(multipart("/api/v1/imports/production").file(new MockMultipartFile("file", "outside.csv", "text/csv", """
                        productCode,objectTypeCode,regionCode,cultivarCode,surveyDate,cultivatedAreaMu,yieldPerMuKilograms,PROD_REPORTER_NAME,PROD_REPORTER_PHONE,PROD_SAMPLE_CONTACT,PROD_SAMPLE_LATITUDE,PROD_SAMPLE_LONGITUDE,evidencePhotoId
                        CORN,FARMER,231100,,2026-07-31,10,20,导入填报员,13800000000,13900000000,47.3543,123.9182,00000000-0000-0000-0000-000000000024
                        """.getBytes(StandardCharsets.UTF_8)))
                        .param("productCode", "CORN").param("objectTypeCode", "FARMER")
                        .header("Idempotency-Key", "outside-scope-import").principal(() -> "limited-importer"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("ACCESS_REGION_DENIED"));
        MockMultipartFile file = new MockMultipartFile("file", "production.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));
        String response = mvc.perform(multipart("/api/v1/imports/production").file(file)
                        .param("productCode", "CORN").param("objectTypeCode", "FARMER")
                        .header("Idempotency-Key", "production-import-1").principal(() -> "production-tester"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.statusCode").value("COMPLETED_WITH_ERRORS"))
                .andExpect(jsonPath("$.data.importedRows").value(0)).andExpect(jsonPath("$.data.failedRows").value(2))
                .andReturn().getResponse().getContentAsString();
        UUID jobId = UUID.fromString(response.replaceFirst("(?s).*?\\\"id\\\":\\\"([^\\\"]+)\\\".*", "$1"));

        mvc.perform(get("/api/v1/imports/production/{jobId}/errors", jobId).principal(() -> "production-tester"))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("IMPORT_ROW_VALUE_FORMAT")));
        assertThat(importErrorDownloadAuditCount(jobId)).isEqualTo(1);
        mvc.perform(get("/api/v1/imports/production/{jobId}/errors", jobId)
                        .principal(() -> "market-tester"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IMPORT_ERROR_FILE_NOT_ALLOWED"));
        assertThat(importErrorDownloadAuditCount(jobId)).isEqualTo(1);

        mvc.perform(multipart("/api/v1/imports/production").file(file)
                        .param("productCode", "CORN").param("objectTypeCode", "FARMER")
                        .header("Idempotency-Key", "production-import-1").principal(() -> "production-tester"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.id").value(jobId.toString()))
                .andExpect(jsonPath("$.data.importedRows").value(0));
        assertThat(jdbc.sql("SELECT count(*) FROM production.production_record").query(Long.class).single()).isZero();

        mvc.perform(post("/api/v1/imports/production/{jobId}/retries", jobId).principal(() -> "production-tester"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.retryOf").value(jobId.toString()))
                .andExpect(jsonPath("$.data.importedRows").value(0)).andExpect(jsonPath("$.data.failedRows").value(2));
        assertThat(jdbc.sql("SELECT count(*) FROM production.production_record").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT action_code FROM platform.business_audit_event ORDER BY occurred_at,event_id")
                .query(String.class).list()).contains("IMPORT_JOB_COMPLETED");
    }

    private long importErrorDownloadAuditCount(UUID jobId) {
        return jdbc.sql("""
                SELECT count(*) FROM platform.business_audit_event
                WHERE aggregate_type='IMPORT_JOB' AND aggregate_id=:id
                  AND action_code='IMPORT_ERROR_FILE_DOWNLOADED'
                """).param("id", jobId.toString()).query(Long.class).single();
    }
}
