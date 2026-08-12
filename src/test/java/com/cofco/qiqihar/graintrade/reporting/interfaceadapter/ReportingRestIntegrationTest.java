package com.cofco.qiqihar.graintrade.reporting.interfaceadapter;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import com.cofco.qiqihar.graintrade.testsupport.ProtectedTestDatabaseConfiguration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.UUID;
import java.io.ByteArrayInputStream;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.AfterEach;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = GrainTradeApplication.class)
@AutoConfigureMockMvc
@UsesProtectedTestDatabase
class ReportingRestIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired DataSource dataSource;
    JdbcClient jdbc;

    @BeforeEach void clean() {
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("TRUNCATE platform.business_audit_event,reporting.report_audit_event,reporting.report_publication,reporting.report_export_task,reporting.report_preview,reporting.approved_dataset,production.production_record RESTART IDENTITY CASCADE").update();
        jdbc.sql("DELETE FROM platform.security_user_region_scope").update();
        jdbc.sql("DELETE FROM platform.security_user_role").update();
        jdbc.sql("DELETE FROM platform.security_user").update();
        jdbc.sql("DELETE FROM platform.work_unit_region_scope").update();
        jdbc.sql("DELETE FROM platform.work_unit").update();
        jdbc.sql("""
                INSERT INTO platform.business_period(code,name,starts_on,ends_on,sort_order,marketing_year_code)
                VALUES('2026-Q3','2026年第三季度',DATE '2026-07-01',DATE '2026-09-30',202603,'2026/27')
                ON CONFLICT(code) DO NOTHING
                """).update();
        jdbc.sql("""
                INSERT INTO platform.work_unit(code,name,sort_order)
                VALUES ('QI','齐齐哈尔工作单位',9001),('HEI','黑河工作单位',9002)
                """).update();
        jdbc.sql("""
                INSERT INTO platform.work_unit_region_scope(work_unit_code,region_code)
                VALUES ('QI','230200'),('QI','230202'),('HEI','231100')
                """).update();
        jdbc.sql("""
                INSERT INTO platform.security_user(subject_id,display_name,work_unit_code)
                VALUES ('reporter','报表专员','QI'),('publisher','报表发布员','QI'),
                       ('limited-reporter','区县报表专员','QI'),('outside-unit-reporter','外单位报表专员','HEI')
                """).update();
        jdbc.sql("""
                INSERT INTO platform.security_user_role(subject_id,role_code)
                VALUES ('reporter','REPORTER'),('publisher','REPORT_PUBLISHER'),
                       ('limited-reporter','REPORTER'),('outside-unit-reporter','REPORTER')
                """).update();
        jdbc.sql("""
                INSERT INTO platform.security_user_region_scope(subject_id,region_code)
                VALUES ('reporter','230200'),('publisher','230200'),
                       ('limited-reporter','230202'),('outside-unit-reporter','231100')
                """).update();
    }

    @AfterEach void cleanAfterEach() {
        clean();
        ProtectedTestDatabaseConfiguration.provisionSecurityTestSubjects(jdbc);
    }

    @Test void requiresApprovedDataThenPreviewsExportsAndPublishes() throws Exception {
        String body = "{\"definitionCode\":\"PRODUCTION_DAILY\",\"productCode\":\"CORN\",\"regionLevel\":\"PREFECTURE\",\"regionCode\":\"230200\",\"periodCode\":\"2026-Q3\"}";
        mvc.perform(post("/api/v1/reports/previews").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/reports/previews").principal(() -> "limited-reporter").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("ACCESS_REGION_DENIED"));
        mvc.perform(post("/api/v1/reports/previews").principal(() -> "outside-unit-reporter").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("ACCESS_REGION_DENIED"));
        mvc.perform(post("/api/v1/reports/previews").principal(() -> "reporter").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("REPORT_APPROVED_DATA_REQUIRED"));
        jdbc.sql("""
                INSERT INTO production.production_record(record_id,product_code,object_type_code,region_code,survey_date,reported_at,cultivated_area_mu,yield_per_mu_kg,status_code,last_modified_by)
                VALUES(:id,'CORN','FARMER','230200',current_date,now(),100,20,'APPROVED','report-test')""").param("id", UUID.randomUUID().toString()).update();
        String preview = mvc.perform(post("/api/v1/reports/previews").principal(() -> "reporter").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.lines[0].value").value("1"))
                .andExpect(jsonPath("$.data.dataCutoffLabel").value("2026年第三季度"))
                .andReturn().getResponse().getContentAsString().replaceAll(".*\\\"id\\\":\\\"([^\\\"]+).*", "$1");
        String export = mvc.perform(post("/api/v1/reports/previews/{id}/exports",preview).principal(() -> "reporter").contentType(MediaType.APPLICATION_JSON).content("{\"formatCode\":\"CSV\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString().replaceAll(".*\\\"id\\\":\\\"([^\\\"]+).*", "$1");
        mvc.perform(get("/api/v1/reports/exports/{id}/content", export))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/reports/exports/{id}/content", export).principal(() -> "reporter"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")))
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("报告名称")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("核定数据条数")));
        mvc.perform(post("/api/v1/reports/previews/{id}/publications",preview).principal(() -> "publisher").contentType(MediaType.APPLICATION_JSON).content("{\"exportTaskId\":\""+export+"\",\"expectedVersion\":0}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.previewId").value(preview));
        assertThat(jdbc.sql("SELECT count(*) FROM reporting.report_audit_event").query(Long.class).single()).isEqualTo(3L);
        assertThat(jdbc.sql("SELECT count(*) FROM platform.business_audit_event").query(Long.class).single()).isEqualTo(4L);
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM platform.business_audit_event").update())
                .hasMessageContaining("business audit events are immutable");
    }

    @Test void changesDatasetDigestWhenApprovedSourceChangesButRecordCountDoesNot() throws Exception {
        String request = "{\"definitionCode\":\"PRODUCTION_DAILY\",\"productCode\":\"CORN\","
                + "\"regionLevel\":\"PREFECTURE\",\"regionCode\":\"230200\",\"periodCode\":\"2026-Q3\"}";
        jdbc.sql("""
                INSERT INTO production.production_record(record_id,product_code,object_type_code,region_code,
                    survey_date,reported_at,cultivated_area_mu,yield_per_mu_kg,status_code,last_modified_by)
                VALUES('digest-source-a','CORN','FARMER','230202',DATE '2026-08-09',
                    TIMESTAMPTZ '2026-08-09 12:34:56+08',100,20,'APPROVED','report-test')
                """).update();

        String firstPreview = mvc.perform(post("/api/v1/reports/previews").principal(() -> "reporter")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.lines[0].value").value("1"))
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\\\"id\\\":\\\"([^\\\"]+).*", "$1");
        String firstDigest = jdbc.sql("""
                SELECT dataset.immutable_digest
                FROM reporting.report_preview preview
                JOIN reporting.approved_dataset dataset ON dataset.dataset_id=preview.dataset_id
                WHERE preview.preview_id=CAST(:preview AS uuid)
                """).param("preview", firstPreview).query(String.class).single();

        String replayedPreview = mvc.perform(post("/api/v1/reports/previews").principal(() -> "reporter")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\\\"id\\\":\\\"([^\\\"]+).*", "$1");
        String replayedDigest = jdbc.sql("""
                SELECT dataset.immutable_digest
                FROM reporting.report_preview preview
                JOIN reporting.approved_dataset dataset ON dataset.dataset_id=preview.dataset_id
                WHERE preview.preview_id=CAST(:preview AS uuid)
                """).param("preview", replayedPreview).query(String.class).single();
        assertThat(replayedDigest).isEqualTo(firstDigest);

        jdbc.sql("DELETE FROM production.production_record WHERE record_id='digest-source-a'").update();
        jdbc.sql("""
                INSERT INTO production.production_record(record_id,product_code,object_type_code,region_code,
                    survey_date,reported_at,cultivated_area_mu,yield_per_mu_kg,status_code,last_modified_by)
                VALUES('digest-source-b','CORN','FARMER','230202',DATE '2026-08-09',
                    TIMESTAMPTZ '2026-08-09 12:34:56+08',100,20,'APPROVED','report-test')
                """).update();

        String secondPreview = mvc.perform(post("/api/v1/reports/previews").principal(() -> "reporter")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.lines[0].value").value("1"))
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\\\"id\\\":\\\"([^\\\"]+).*", "$1");
        String secondDigest = jdbc.sql("""
                SELECT dataset.immutable_digest
                FROM reporting.report_preview preview
                JOIN reporting.approved_dataset dataset ON dataset.dataset_id=preview.dataset_id
                WHERE preview.preview_id=CAST(:preview AS uuid)
                """).param("preview", secondPreview).query(String.class).single();

        assertThat(secondDigest).isNotEqualTo(firstDigest);
        assertThat(jdbc.sql("""
                SELECT dataset.immutable_digest
                FROM reporting.report_preview preview
                JOIN reporting.approved_dataset dataset ON dataset.dataset_id=preview.dataset_id
                WHERE preview.preview_id=CAST(:preview AS uuid)
                """).param("preview", firstPreview).query(String.class).single()).isEqualTo(firstDigest);
    }

    @Test void listsDailyWeeklyAndMonthlyReportsForEveryScopedBusinessDomain() throws Exception {
        mvc.perform(get("/api/v1/reports/parameter-options").principal(() -> "reporter"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.definitions.length()").value(12))
                .andExpect(jsonPath("$.data.definitions[*].code").value(org.hamcrest.Matchers.containsInAnyOrder(
                        "PRODUCTION_DAILY", "PRODUCTION_WEEKLY", "PRODUCTION_MONTHLY",
                        "MARKET_DAILY", "MARKET_WEEKLY", "MARKET_MONTHLY",
                        "LOGISTICS_DAILY", "LOGISTICS_WEEKLY", "LOGISTICS_MONTHLY",
                        "SUPPLY_DAILY", "SUPPLY_WEEKLY", "SUPPLY_MONTHLY")))
                .andExpect(jsonPath("$.data.definitions[*].businessDomain", org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.hasItems("COMPREHENSIVE", "SUBMISSION"))));
    }

    @Test void evaluatesEveryActiveReportDomainAgainstItsDatabaseSource() throws Exception {
        for (String definition : java.util.List.of("MARKET_DAILY", "LOGISTICS_WEEKLY", "SUPPLY_MONTHLY")) {
            String request = "{\"definitionCode\":\"" + definition + "\",\"productCode\":\"CORN\","
                    + "\"regionLevel\":\"PREFECTURE\",\"regionCode\":\"230200\",\"periodCode\":\"2026-Q3\"}";
            mvc.perform(post("/api/v1/reports/previews").principal(() -> "reporter")
                            .contentType(MediaType.APPLICATION_JSON).content(request))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("REPORT_APPROVED_DATA_REQUIRED"));
        }
    }

    @Test void exportsTheServerOwnedScopedPreviewAsAnXlsxWorkbook() throws Exception {
        jdbc.sql("""
                INSERT INTO production.production_record(record_id,product_code,object_type_code,region_code,
                    survey_date,reported_at,cultivated_area_mu,yield_per_mu_kg,status_code,last_modified_by)
                VALUES(:id,'CORN','FARMER','230200',DATE '2026-08-09',now(),100,20,'APPROVED','report-test')
                """).param("id", UUID.randomUUID().toString()).update();
        String request = "{\"definitionCode\":\"PRODUCTION_DAILY\",\"productCode\":\"CORN\","
                + "\"regionLevel\":\"PREFECTURE\",\"regionCode\":\"230200\",\"periodCode\":\"2026-Q3\"}";
        String preview = mvc.perform(post("/api/v1/reports/previews").principal(() -> "reporter")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()
                .replaceAll(".*\\\"id\\\":\\\"([^\\\"]+).*", "$1");
        String export = mvc.perform(post("/api/v1/reports/previews/{id}/exports", preview)
                        .principal(() -> "reporter").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"formatCode\":\"XLSX\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.formatCode").value("XLSX"))
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\\\"id\\\":\\\"([^\\\"]+).*", "$1");

        byte[] workbook = mvc.perform(get("/api/v1/reports/exports/{id}/content", export)
                        .principal(() -> "reporter"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString(".xlsx")))
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(workbook).startsWith(80, 75, 3, 4);
        StringBuilder xml = new StringBuilder();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(workbook))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.getName().endsWith(".xml")) {
                    xml.append(new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                }
            }
        }
        assertThat(xml).contains("齐齐哈尔市玉米产情日报", "核定数据条数", "2026年第三季度");
    }

    @Test void exportsTheServerOwnedScopedPreviewAsAPdfDocument() throws Exception {
        jdbc.sql("""
                INSERT INTO production.production_record(record_id,product_code,object_type_code,region_code,
                    survey_date,reported_at,cultivated_area_mu,yield_per_mu_kg,status_code,last_modified_by)
                VALUES(:id,'CORN','FARMER','230200',DATE '2026-08-09',now(),100,20,'APPROVED','report-test')
                """).param("id", UUID.randomUUID().toString()).update();
        String request = "{\"definitionCode\":\"PRODUCTION_DAILY\",\"productCode\":\"CORN\","
                + "\"regionLevel\":\"PREFECTURE\",\"regionCode\":\"230200\",\"periodCode\":\"2026-Q3\"}";
        String preview = mvc.perform(post("/api/v1/reports/previews").principal(() -> "reporter")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()
                .replaceAll(".*\\\"id\\\":\\\"([^\\\"]+).*", "$1");
        String export = mvc.perform(post("/api/v1/reports/previews/{id}/exports", preview)
                        .principal(() -> "reporter").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"formatCode\":\"PDF\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.formatCode").value("PDF"))
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\\\"id\\\":\\\"([^\\\"]+).*", "$1");

        byte[] pdf = mvc.perform(get("/api/v1/reports/exports/{id}/content", export)
                        .principal(() -> "reporter"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/pdf"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString(".pdf")))
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(new String(pdf, 0, 8, java.nio.charset.StandardCharsets.US_ASCII)).startsWith("%PDF-1.4");
        assertThat(pdf.length).isGreaterThan(1_000);
    }

    @Test void exposesAuditableScopeAndCutoffAndExportsTheSameSnapshotAsDocx() throws Exception {
        jdbc.sql("""
                INSERT INTO production.production_record(record_id,product_code,object_type_code,region_code,
                    survey_date,reported_at,cultivated_area_mu,yield_per_mu_kg,status_code,last_modified_by)
                VALUES(:id,'CORN','FARMER','230202',DATE '2026-08-09',
                    TIMESTAMPTZ '2026-08-09 12:34:56+08',100,20,'APPROVED','report-test')
                """).param("id", UUID.randomUUID().toString()).update();
        String request = "{\"definitionCode\":\"PRODUCTION_DAILY\",\"productCode\":\"CORN\","
                + "\"regionLevel\":\"PREFECTURE\",\"regionCode\":\"230200\",\"periodCode\":\"2026-Q3\"}";
        String response = mvc.perform(post("/api/v1/reports/previews").principal(() -> "reporter")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.lines[?(@.label == '报告范围')].value")
                        .value(org.hamcrest.Matchers.hasItem("齐齐哈尔市 / 玉米 / 2026年第三季度")))
                .andExpect(jsonPath("$.data.lines[?(@.label == '精确数据截止')].value")
                        .value(org.hamcrest.Matchers.hasItem("2026年08月09日 12:34:56")))
                .andExpect(jsonPath("$.data.lines[?(@.label == '数据分级')].value")
                        .value(org.hamcrest.Matchers.hasItem("内部")))
                .andExpect(jsonPath("$.data.lines[?(@.label == '审计编号')].value")
                        .value(org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.matchesPattern(
                                "[0-9a-f-]{36}"))))
                .andReturn().getResponse().getContentAsString();
        String preview = response.replaceAll(".*\\\"id\\\":\\\"([^\\\"]+).*", "$1");

        String export = mvc.perform(post("/api/v1/reports/previews/{id}/exports", preview)
                        .principal(() -> "reporter").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"formatCode\":\"DOCX\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.formatCode").value("DOCX"))
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\\\"id\\\":\\\"([^\\\"]+).*", "$1");

        byte[] document = mvc.perform(get("/api/v1/reports/exports/{id}/content", export)
                        .principal(() -> "reporter"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString(".docx")))
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(document).startsWith(80, 75, 3, 4);
        StringBuilder xml = new StringBuilder();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(document))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.getName().endsWith(".xml")) {
                    xml.append(new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                }
            }
        }
        assertThat(xml).contains("报告范围", "精确数据截止", "审计编号", "数据分级", "内部")
                .doesNotContain("production.production_record", "SUM(cultivated_area_mu)",
                        "2026-08-09T04:34:56Z");
    }

    @Test void scopesProductionReportsToDescendantRegionsAndTheRequestedCultivar() throws Exception {
        String requested = UUID.randomUUID().toString();
        String other = UUID.randomUUID().toString();
        jdbc.sql("""
                INSERT INTO production.production_record(record_id,product_code,object_type_code,region_code,
                    survey_date,reported_at,cultivated_area_mu,yield_per_mu_kg,status_code,last_modified_by)
                VALUES(:requested,'CORN','FARMER','230202',DATE '2026-08-09',now(),100,20,'APPROVED','report-test'),
                      (:other,'CORN','FARMER','230202',DATE '2026-08-09',now(),100,20,'APPROVED','report-test')
                """).param("requested", requested).param("other", other).update();
        jdbc.sql("""
                INSERT INTO production.production_record_submission_metadata(record_id,field_code,value)
                VALUES(:requested,'PROD_CULTIVAR_NAME','龙单86'),
                      (:other,'PROD_CULTIVAR_NAME','德美亚3号')
                """).param("requested", requested).param("other", other).update();

        String broadRequest = "{\"definitionCode\":\"PRODUCTION_DAILY\",\"productCode\":\"CORN\","
                + "\"regionLevel\":\"PREFECTURE\",\"regionCode\":\"230200\",\"periodCode\":\"2026-Q3\"}";
        mvc.perform(post("/api/v1/reports/previews").principal(() -> "reporter")
                        .contentType(MediaType.APPLICATION_JSON).content(broadRequest))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.lines[0].value").value("2"));

        String cultivarRequest = "{\"definitionCode\":\"PRODUCTION_DAILY\",\"productCode\":\"CORN\","
                + "\"cultivarCode\":\"龙单86\",\"regionLevel\":\"PREFECTURE\","
                + "\"regionCode\":\"230200\",\"periodCode\":\"2026-Q3\"}";
        mvc.perform(post("/api/v1/reports/previews").principal(() -> "reporter")
                        .contentType(MediaType.APPLICATION_JSON).content(cultivarRequest))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.lines[0].value").value("1"));
    }

    @Test void excludesApprovedRowsWhoseSurveyPeriodIsStillPendingGovernance() throws Exception {
        jdbc.sql("""
                INSERT INTO production.production_record(record_id,product_code,object_type_code,region_code,
                    survey_date,reported_at,cultivated_area_mu,yield_per_mu_kg,status_code,last_modified_by,
                    survey_period_governance_state)
                VALUES('report-confirmed','CORN','FARMER','230202',DATE '2026-08-09',
                         TIMESTAMPTZ '2026-08-09 12:34:56+08',100,20,'APPROVED','report-test','CONFIRMED'),
                      ('report-pending','CORN','FARMER','230202',DATE '2026-08-10',
                         TIMESTAMPTZ '2026-08-10 23:59:59+08',200,30,'APPROVED','report-test','PENDING_GOVERNANCE')
                """).update();
        jdbc.sql("""
                UPDATE production.production_record
                SET survey_period_governance_state='PENDING_GOVERNANCE'
                WHERE record_id='report-pending'
                """).update();
        String request = "{\"definitionCode\":\"PRODUCTION_DAILY\",\"productCode\":\"CORN\","
                + "\"regionLevel\":\"PREFECTURE\",\"regionCode\":\"230200\",\"periodCode\":\"2026-Q3\"}";

        mvc.perform(post("/api/v1/reports/previews").principal(() -> "reporter")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.lines[0].value").value("1"))
                .andExpect(jsonPath("$.data.lines[?(@.label == '精确数据截止')].value")
                        .value(org.hamcrest.Matchers.hasItem("2026年08月09日 12:34:56")));
    }
}
