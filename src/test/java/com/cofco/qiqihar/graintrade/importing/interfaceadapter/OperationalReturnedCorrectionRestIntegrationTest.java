package com.cofco.qiqihar.graintrade.importing.interfaceadapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.logistics.application.LogisticsDraft;
import com.cofco.qiqihar.graintrade.logistics.application.LogisticsService;
import com.cofco.qiqihar.graintrade.production.application.ProductionDraft;
import com.cofco.qiqihar.graintrade.production.application.ProductionImportPort;
import com.cofco.qiqihar.graintrade.production.application.ProductionRecordService;
import com.cofco.qiqihar.graintrade.shared.security.application.InternalSecuritySubjectScope;
import com.cofco.qiqihar.graintrade.testsupport.ProtectedTestDatabaseConfiguration;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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
class OperationalReturnedCorrectionRestIntegrationTest {
    private static final String XLSX =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Autowired MockMvc mvc;
    @Autowired DataSource dataSource;
    @Autowired ProductionImportPort productionImports;
    @Autowired ProductionRecordService production;
    @Autowired LogisticsService logistics;
    @Autowired InternalSecuritySubjectScope subjects;
    private JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        jdbc = JdbcClient.create(dataSource);
        ProtectedTestDatabaseConfiguration.provisionSecurityTestSubjects(jdbc);
        cleanBusinessRows();
    }

    @AfterEach
    void cleanUp() {
        ProtectedTestDatabaseConfiguration.provisionSecurityTestSubjects(jdbc);
        cleanBusinessRows();
    }

    @Test
    void productionCorrectionUpdatesTheOriginalAndReopensTheRealWorkItem() throws Exception {
        String id = subjects.callAs("production-tester", () ->
                productionImports.importAndSubmit(productionDraft()));
        subjects.callAs("market-tester", () ->
                production.returnForCorrection(id, 1, "补充产情原单内容"));
        long recordCount = count("production.production_record");

        byte[] workbook = mvc.perform(get(
                                "/api/v1/imports/production/returned-corrections/template")
                        .param("productCode", "CORN")
                        .principal(() -> "production-tester"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        mvc.perform(multipart("/api/v1/imports/production/returned-corrections")
                        .file(correctionFile("产情退回记录修正表.xlsx", workbook))
                        .param("productCode", "CORN")
                        .header("Idempotency-Key", "production-returned-correction")
                        .principal(() -> "production-tester"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.importedRows").value(1))
                .andExpect(jsonPath("$.data.failedRows").value(0));

        assertThat(count("production.production_record")).isEqualTo(recordCount);
        assertThat(recordState("production.production_record", "record_id", id))
                .isEqualTo("PENDING_REVIEW:4");
        assertPendingWorkItem("PRODUCTION", "PRODUCTION", id);
        assertSubmissionEvent("PRODUCTION_RECORD", id);

        String replayJobId = mvc.perform(multipart(
                                "/api/v1/imports/production/returned-corrections")
                        .file(correctionFile("产情退回记录修正表.xlsx", workbook))
                        .param("productCode", "CORN")
                        .header("Idempotency-Key", "production-returned-correction-replay")
                        .principal(() -> "production-tester"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.importedRows").value(0))
                .andExpect(jsonPath("$.data.failedRows").value(1))
                .andReturn().getResponse().getContentAsString()
                .replaceFirst("(?s).*?\"id\":\"([^\"]+)\".*", "$1");
        mvc.perform(get("/api/v1/imports/production/returned-corrections/{id}/errors",
                                replayJobId)
                        .principal(() -> "production-tester"))
                .andExpect(status().isOk());
        assertErrorDownloadAudit(replayJobId, "production-tester");

        expectPendingAnalysisWithoutMetric("production", "EXPECTED_OUTPUT");
        mvc.perform(post("/api/v1/production-records/{id}/approve", id)
                        .principal(() -> "market-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));
        expectApprovedAnalysisMetric("production", "EXPECTED_OUTPUT", "50.0000");
        expectOverviewMetric("PRODUCTION_ESTIMATED_OUTPUT", "50000");
        assertCompletedWorkItem("PRODUCTION", "PRODUCTION", id, "production-tester");
    }

    @Test
    void logisticsCorrectionUpdatesTheOriginalAndReopensTheRealWorkItem() throws Exception {
        String id = subjects.callAs("logistics-tester", () ->
                logistics.create(logisticsDraft()).id());
        subjects.callAs("logistics-tester", () -> logistics.submit(id, 0));
        subjects.callAs("production-tester", () ->
                logistics.returned(id, 1, "补充物流原单内容"));
        long recordCount = count("logistics.route_event");

        byte[] workbook = mvc.perform(get(
                                "/api/v1/imports/logistics/returned-corrections/template")
                        .param("productCode", "CORN")
                        .principal(() -> "logistics-tester"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        mvc.perform(multipart("/api/v1/imports/logistics/returned-corrections")
                        .file(correctionFile("物流退回记录修正表.xlsx", workbook))
                        .param("productCode", "CORN")
                        .header("Idempotency-Key", "logistics-returned-correction")
                        .principal(() -> "logistics-tester"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.importedRows").value(1))
                .andExpect(jsonPath("$.data.failedRows").value(0));

        assertThat(count("logistics.route_event")).isEqualTo(recordCount);
        assertThat(recordState("logistics.route_event", "event_id", id))
                .isEqualTo("PENDING_REVIEW:4");
        assertPendingWorkItem("LOGISTICS", "LOGISTICS", id);
        assertSubmissionEvent("LOGISTICS_RECORD", id);

        expectPendingAnalysisWithoutMetric("logistics", "INFLOW_VOLUME");
        mvc.perform(post("/api/v1/logistics-records/{id}/approve", id)
                        .principal(() -> "production-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));
        expectApprovedAnalysisMetric("logistics", "INFLOW_VOLUME", "12.5000");
        expectOverviewLogisticsBusinessValue("12.5");
        assertCompletedWorkItem("LOGISTICS", "LOGISTICS", id, "logistics-tester");
    }

    private void assertPendingWorkItem(String domain, String sourceType, String sourceId)
            throws Exception {
        mvc.perform(get("/api/v1/work-items")
                        .param("scope", "PENDING")
                        .param("domain", domain)
                        .param("page", "0")
                        .param("pageSize", "20")
                        .principal(() -> "production-tester"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].sourceType").value(sourceType))
                .andExpect(jsonPath("$.data.items[0].sourceId").value(sourceId))
                .andExpect(jsonPath("$.data.items[0].statusCode").value("TO_REVIEW"));
    }

    private void assertCompletedWorkItem(
            String domain, String sourceType, String sourceId, String ownerSubjectId)
            throws Exception {
        mvc.perform(get("/api/v1/work-items")
                        .param("scope", "COMPLETED")
                        .param("domain", domain)
                        .param("page", "0")
                        .param("pageSize", "20")
                        .principal(() -> ownerSubjectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].sourceType").value(sourceType))
                .andExpect(jsonPath("$.data.items[0].sourceId").value(sourceId))
                .andExpect(jsonPath("$.data.items[0].workflowNode").value("已完成"))
                .andExpect(jsonPath("$.data.items[0].statusCode").value((Object) null));
    }

    private void expectPendingAnalysisWithoutMetric(String domain, String metricCode)
            throws Exception {
        mvc.perform(get("/api/v1/observable-analysis/snapshots")
                        .principal(() -> "production-tester")
                        .queryParam("productCode", "CORN")
                        .queryParam("regionCode", "230200")
                        .queryParam("surveyYear", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.coverage.pendingReviewRecordCount").value(1))
                .andExpect(jsonPath("$.data." + domain
                        + ".metrics[?(@.code == '" + metricCode + "')]").isEmpty());
    }

    private void expectApprovedAnalysisMetric(String domain, String metricCode, String value)
            throws Exception {
        mvc.perform(get("/api/v1/observable-analysis/snapshots")
                        .principal(() -> "production-tester")
                        .queryParam("productCode", "CORN")
                        .queryParam("regionCode", "230200")
                        .queryParam("surveyYear", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.coverage.pendingReviewRecordCount").value(0))
                .andExpect(jsonPath("$.data." + domain
                                + ".metrics[?(@.code == '" + metricCode + "')].value")
                        .value(org.hamcrest.Matchers.hasItem(value)))
                .andExpect(jsonPath("$.data." + domain
                                + ".metrics[?(@.code == '" + metricCode + "')].sourceCount")
                        .value(org.hamcrest.Matchers.hasItem(1)));
    }

    private void expectOverviewMetric(String metricCode, String value) throws Exception {
        mvc.perform(get("/api/v1/overview/dashboard")
                        .principal(() -> "production-tester")
                        .queryParam("productCode", "CORN")
                        .queryParam("regionCode", "230200")
                        .queryParam("year", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.metrics[?(@.code == '" + metricCode + "')].value")
                        .value(org.hamcrest.Matchers.hasItem(value)))
                .andExpect(jsonPath("$.data.metrics[?(@.code == '" + metricCode
                                + "')].sourceCount")
                        .value(1));
    }

    private void expectOverviewLogisticsBusinessValue(String value) throws Exception {
        mvc.perform(get("/api/v1/overview/dashboard")
                        .principal(() -> "production-tester")
                        .queryParam("productCode", "CORN")
                        .queryParam("regionCode", "230200")
                        .queryParam("year", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.metrics[?(@.code == 'LOGISTICS_INFLOW_VOLUME')]").isEmpty())
                .andExpect(jsonPath(
                                "$.data.businessTables[?(@.code == 'LOGISTICS')].rows[*].values.LOG_ROUTE_VOLUME.value")
                        .value(org.hamcrest.Matchers.hasItem(value)));
    }

    private void assertSubmissionEvent(String aggregateType, String id) {
        assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.business_event_outbox
                WHERE aggregate_type=:type AND aggregate_id=:id
                  AND action_code=:action
                """).param("type", aggregateType).param("id", id)
                .param("action", aggregateType + "_SUBMITTED")
                .query(Long.class).single()).isEqualTo(2);
    }

    private void assertErrorDownloadAudit(String jobId, String actor) {
        assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.business_audit_event
                WHERE aggregate_type='IMPORT_JOB' AND aggregate_id=:jobId
                  AND action_code='IMPORT_ERROR_FILE_DOWNLOADED'
                  AND actor_subject_id=:actor
                """).param("jobId", jobId).param("actor", actor)
                .query(Long.class).single()).isOne();
    }

    private ProductionDraft productionDraft() {
        return new ProductionDraft(
                "CORN", "FARMER", "230200", null,
                LocalDate.of(2026, 8, 1), new BigDecimal("100"), new BigDecimal("500"),
                Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of(
                        "PROD_SAMPLE_NAME", "产情退回修正样本",
                        "PROD_SURVEYOR_NAME", "王雷",
                        "PROD_SURVEYOR_PHONE", "13800000000",
                        "PROD_SAMPLE_CONTACT", "13900000000",
                        "PROD_SAMPLE_LATITUDE", "47.3543",
                        "PROD_SAMPLE_LONGITUDE", "123.9182"),
                List.of(), 2026, 8);
    }

    private LogisticsDraft logisticsDraft() {
        return new LogisticsDraft("CORN", Map.ofEntries(
                Map.entry("surveyYear", "2026"),
                Map.entry("surveyMonth", "8"),
                Map.entry("LOG_SAMPLE_NAME", "物流退回修正样本"),
                Map.entry("LOG_REGION", "230200"),
                Map.entry("LOG_TRANSPORT_MODE", "RAIL"),
                Map.entry("LOG_DIRECTION", "INFLOW"),
                Map.entry("LOG_ROUTE_VOLUME", "12.5000"),
                Map.entry("LOG_FREIGHT_RATE", "80.2500"),
                Map.entry("LOG_BOARD_PRICE", "2650.0000"),
                Map.entry("LOG_SURVEYOR_NAME", "王雷"),
                Map.entry("LOG_SURVEYOR_PHONE", "13800000000"),
                Map.entry("LOG_SAMPLE_CONTACT", "13900000000"),
                Map.entry("LOG_SAMPLE_LATITUDE", "47.354300"),
                Map.entry("LOG_SAMPLE_LONGITUDE", "123.918200")));
    }

    private MockMultipartFile correctionFile(String filename, byte[] bytes) {
        return new MockMultipartFile("file", filename, XLSX, bytes);
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private String recordState(String table, String idColumn, String id) {
        return jdbc.sql("SELECT status_code || ':' || version FROM " + table
                        + " WHERE " + idColumn + "::text=:id")
                .param("id", id).query(String.class).single();
    }

    private void cleanBusinessRows() {
        jdbc.sql("""
                TRUNCATE workflow.work_item,platform.import_row_result,platform.import_job,
                  platform.business_audit_event,production.production_record,
                  logistics.route_event,evidence.evidence_photo
                RESTART IDENTITY CASCADE
                """).update();
        jdbc.sql("""
                DELETE FROM registry.sample_point
                WHERE canonical_name IN ('产情退回修正样本','物流退回修正样本')
                  AND created_by IN ('production-tester','logistics-tester')
                """).update();
    }
}
