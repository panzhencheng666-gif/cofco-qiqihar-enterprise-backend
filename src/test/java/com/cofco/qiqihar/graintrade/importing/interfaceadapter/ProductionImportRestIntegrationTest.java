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
    @Autowired MockMvc mvc;
    @Autowired DataSource dataSource;
    JdbcClient jdbc;

    @BeforeEach
    void clean() {
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("""
                TRUNCATE platform.import_row_result,platform.import_job,platform.business_audit_event,
                  production.production_record RESTART IDENTITY CASCADE
                """).update();
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

    @AfterEach void cleanAfterEach() { clean(); }

    @Test
    void rejectsPathologicalAndOverPrecisionImportDecimalsWithoutProductionWrites() throws Exception {
        String csv = """
                productCode,objectTypeCode,regionCode,cultivarCode,surveyDate,cultivatedAreaMu,yieldPerMuKilograms,PROD_REPORTER_NAME,PROD_REPORTER_PHONE,PROD_SAMPLE_CONTACT,PROD_SAMPLE_LATITUDE,PROD_SAMPLE_LONGITUDE
                CORN,FARMER,230200,,2026-07-31,1E999999999,20,导入填报员,13800000000,13900000000,47.3543,123.9182
                CORN,FARMER,230200,,2026-07-31,100000000000000.0000,20,导入填报员,13800000000,13900000000,47.3543,123.9182
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
                        "productCode,objectTypeCode,regionCode,cultivarCode,surveyDate,cultivatedAreaMu,yieldPerMuKilograms,PROD_REPORTER_NAME,PROD_REPORTER_PHONE,PROD_SAMPLE_CONTACT,PROD_SAMPLE_LATITUDE,PROD_SAMPLE_LONGITUDE\n"));

        String csv = """
                productCode,objectTypeCode,regionCode,cultivarCode,surveyDate,cultivatedAreaMu,yieldPerMuKilograms,PROD_REPORTER_NAME,PROD_REPORTER_PHONE,PROD_SAMPLE_CONTACT,PROD_SAMPLE_LATITUDE,PROD_SAMPLE_LONGITUDE
                CORN,FARMER,230200,,2026-07-31,10.5,20,导入填报员,13800000000,13900000000,47.3543,123.9182
                SOYBEAN,FARMER,230200,,bad-date,5,30,导入填报员,13800000000,13900000000,47.3543,123.9182
                """;
        mvc.perform(multipart("/api/v1/imports/production").file(new MockMultipartFile("file", "outside.csv", "text/csv", """
                        productCode,objectTypeCode,regionCode,cultivarCode,surveyDate,cultivatedAreaMu,yieldPerMuKilograms,PROD_REPORTER_NAME,PROD_REPORTER_PHONE,PROD_SAMPLE_CONTACT,PROD_SAMPLE_LATITUDE,PROD_SAMPLE_LONGITUDE
                        CORN,FARMER,231100,,2026-07-31,10,20,导入填报员,13800000000,13900000000,47.3543,123.9182
                        """.getBytes(StandardCharsets.UTF_8)))
                        .header("Idempotency-Key", "outside-scope-import").principal(() -> "limited-importer"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("ACCESS_REGION_DENIED"));
        MockMultipartFile file = new MockMultipartFile("file", "production.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));
        String response = mvc.perform(multipart("/api/v1/imports/production").file(file)
                        .header("Idempotency-Key", "production-import-1").principal(() -> "production-tester"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.statusCode").value("COMPLETED_WITH_ERRORS"))
                .andExpect(jsonPath("$.data.importedRows").value(1)).andExpect(jsonPath("$.data.failedRows").value(1))
                .andReturn().getResponse().getContentAsString();
        UUID jobId = UUID.fromString(response.replaceFirst("(?s).*?\\\"id\\\":\\\"([^\\\"]+)\\\".*", "$1"));

        mvc.perform(get("/api/v1/imports/production/{jobId}/errors", jobId).principal(() -> "production-tester"))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("IMPORT_ROW_VALUE_FORMAT")));

        mvc.perform(multipart("/api/v1/imports/production").file(file)
                        .header("Idempotency-Key", "production-import-1").principal(() -> "production-tester"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.id").value(jobId.toString()))
                .andExpect(jsonPath("$.data.importedRows").value(1));
        assertThat(jdbc.sql("SELECT count(*) FROM production.production_record").query(Long.class).single()).isEqualTo(1);
        String importedId = jdbc.sql("SELECT record_id FROM production.production_record").query(String.class).single();
        mvc.perform(get("/api/v1/production-records/{id}", importedId).principal(() -> "production-tester"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.submissionMetadata.PROD_REPORTER_NAME").value("导入填报员"))
                .andExpect(jsonPath("$.data.submissionMetadata.PROD_SAMPLE_LONGITUDE").value("123.9182"));
        mvc.perform(get("/api/v1/production-records").principal(() -> "production-tester")
                        .queryParam("productCode", "CORN").queryParam("pageKind", "MONITORING")
                        .queryParam("pageNumber", "0").queryParam("pageSize", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].values.PROD_REPORTER_PHONE").value("13800000000"))
                .andExpect(jsonPath("$.data.items[0].values.PROD_SAMPLE_LATITUDE").value("47.3543"));

        mvc.perform(post("/api/v1/imports/production/{jobId}/retries", jobId).principal(() -> "production-tester"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.retryOf").value(jobId.toString()))
                .andExpect(jsonPath("$.data.importedRows").value(0)).andExpect(jsonPath("$.data.failedRows").value(1));
        assertThat(jdbc.sql("SELECT count(*) FROM production.production_record").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT action_code FROM platform.business_audit_event ORDER BY occurred_at,event_id")
                .query(String.class).list()).contains("PRODUCTION_RECORD_CREATED", "IMPORT_JOB_COMPLETED");
    }
}
