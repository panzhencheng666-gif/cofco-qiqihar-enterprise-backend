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
                  1,repeat('b',64),now(),47.3543,123.9182,'测试水印','production-tester',now())
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
    void rejectsPathologicalAndOverPrecisionImportDecimalsWithoutProductionWrites() throws Exception {
        String csv = """
                productCode,objectTypeCode,regionCode,cultivarCode,surveyDate,cultivatedAreaMu,yieldPerMuKilograms,PROD_REPORTER_NAME,PROD_REPORTER_PHONE,PROD_SAMPLE_CONTACT,PROD_SAMPLE_LATITUDE,PROD_SAMPLE_LONGITUDE,evidencePhotoId
                CORN,FARMER,230200,,2026-07-31,1E999999999,20,导入填报员,13800000000,13900000000,47.3543,123.9182,00000000-0000-0000-0000-000000000021
                CORN,FARMER,230200,,2026-07-31,100000000000000.0000,20,导入填报员,13800000000,13900000000,47.3543,123.9182,00000000-0000-0000-0000-000000000022
                """;

        mvc.perform(multipart("/api/v1/imports/production")
                        .file(new MockMultipartFile("file", "hostile-decimals.csv", "text/csv",
                                csv.getBytes(StandardCharsets.UTF_8)))
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
                SOYBEAN,FARMER,230200,,bad-date,5,30,导入填报员,13800000000,13900000000,47.3543,123.9182,00000000-0000-0000-0000-000000000023
                """.formatted(PHOTO_ID);
        mvc.perform(multipart("/api/v1/imports/production").file(new MockMultipartFile("file", "outside.csv", "text/csv", """
                        productCode,objectTypeCode,regionCode,cultivarCode,surveyDate,cultivatedAreaMu,yieldPerMuKilograms,PROD_REPORTER_NAME,PROD_REPORTER_PHONE,PROD_SAMPLE_CONTACT,PROD_SAMPLE_LATITUDE,PROD_SAMPLE_LONGITUDE,evidencePhotoId
                        CORN,FARMER,231100,,2026-07-31,10,20,导入填报员,13800000000,13900000000,47.3543,123.9182,00000000-0000-0000-0000-000000000024
                        """.getBytes(StandardCharsets.UTF_8)))
                        .header("Idempotency-Key", "outside-scope-import").principal(() -> "limited-importer"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("ACCESS_REGION_DENIED"));
        MockMultipartFile file = new MockMultipartFile("file", "production.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));
        String response = mvc.perform(multipart("/api/v1/imports/production").file(file)
                        .header("Idempotency-Key", "production-import-1").principal(() -> "production-tester"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.statusCode").value("COMPLETED_WITH_ERRORS"))
                .andExpect(jsonPath("$.data.importedRows").value(0)).andExpect(jsonPath("$.data.failedRows").value(2))
                .andReturn().getResponse().getContentAsString();
        UUID jobId = UUID.fromString(response.replaceFirst("(?s).*?\\\"id\\\":\\\"([^\\\"]+)\\\".*", "$1"));

        mvc.perform(get("/api/v1/imports/production/{jobId}/errors", jobId).principal(() -> "production-tester"))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("IMPORT_ROW_VALUE_FORMAT")));

        mvc.perform(multipart("/api/v1/imports/production").file(file)
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
}
