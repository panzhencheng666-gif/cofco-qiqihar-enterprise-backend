package com.cofco.qiqihar.graintrade.importing.interfaceadapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.importing.application.MarketReturnedCorrectionWorkbook;
import com.cofco.qiqihar.graintrade.importing.application.MarketReturnedCorrectionRowService;
import com.cofco.qiqihar.graintrade.importing.infrastructure.BusinessImportWorkbook;
import com.cofco.qiqihar.graintrade.importing.infrastructure.XlsxTable;
import com.cofco.qiqihar.graintrade.market.importing.MarketImportRow;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.application.ConflictException;
import com.cofco.qiqihar.graintrade.shared.application.ResourceNotFoundException;
import com.cofco.qiqihar.graintrade.shared.security.application.InternalSecuritySubjectScope;
import com.cofco.qiqihar.graintrade.testsupport.ProtectedTestDatabaseConfiguration;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = GrainTradeApplication.class)
@AutoConfigureMockMvc
@UsesProtectedTestDatabase
class MarketReturnedCorrectionRestIntegrationTest {
    private static final String COORDINATE_REASON = "地区与经纬度不匹配";

    @Autowired MockMvc mvc;
    @Autowired DataSource dataSource;
    @Autowired MarketReturnedCorrectionRowService rowService;
    @Autowired InternalSecuritySubjectScope subjects;
    private JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        jdbc = JdbcClient.create(dataSource);
        ProtectedTestDatabaseConfiguration.provisionSecurityTestSubjects(jdbc);
        jdbc.sql("""
                TRUNCATE platform.import_row_result,platform.import_job,platform.business_audit_event,
                  platform.business_import_draft,market.market_record,evidence.evidence_photo
                RESTART IDENTITY CASCADE
                """).update();
        boundary("230200", 122, 46, 125, 49);
        boundary("231100", 122, 46, 125, 49);
    }

    @AfterEach
    void clean() {
        jdbc.sql("""
                TRUNCATE platform.import_row_result,platform.import_job,platform.business_audit_event,
                  platform.business_import_draft,market.market_record,evidence.evidence_photo
                RESTART IDENTITY CASCADE
                """).update();
        jdbc.sql("DELETE FROM overview.administrative_boundary WHERE source_url=:source")
                .param("source", "urn:test:returned-market-correction").update();
        jdbc.sql("""
                DELETE FROM registry.sample_point point
                WHERE point.created_by='market-tester'
                  AND NOT EXISTS(SELECT 1 FROM production.production_record record
                    WHERE record.sample_point_id=point.sample_point_id)
                  AND NOT EXISTS(SELECT 1 FROM market.market_record record
                    WHERE record.sample_point_id=point.sample_point_id)
                  AND NOT EXISTS(SELECT 1 FROM logistics.route_event event
                    WHERE event.sample_point_id=point.sample_point_id)
                """).update();
        ProtectedTestDatabaseConfiguration.provisionSecurityTestSubjects(jdbc);
    }

    @Test
    void downloadsOnlyAuthorizedCoordinateMismatchReturns() throws Exception {
        String first = returned("CORN", "FEED_MILL", "230200", "目标样本甲", COORDINATE_REASON);
        String second = returned("CORN", "TRADER", "230200", "目标样本乙", "  " + COORDINATE_REASON + "  ");
        String otherReason = returned(
                "CORN", "TRADER", "230200", "其他原因样本", COORDINATE_REASON + "，请复核");
        String soybean = returned("SOYBEAN", "TRADER", "230200", "大豆样本", COORDINATE_REASON);
        String unauthorized = returned("CORN", "TRADER", "231100", "越权地区样本", COORDINATE_REASON);
        restrictMarketTesterTo("230200");

        long recordCount = count("market.market_record");
        long auditCount = count("platform.business_audit_event");
        long draftCount = count("platform.business_import_draft");
        String stateVersions = recordStateVersions();

        var response = mvc.perform(get("/api/v1/imports/market/returned-corrections/template")
                        .param("productCode", "CORN")
                        .principal(() -> "market-tester"))
                .andExpect(status().isOk())
                .andReturn().getResponse();

        assertThat(response.getContentType())
                .isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(ContentDisposition.parse(response.getHeader(HttpHeaders.CONTENT_DISPOSITION)).getFilename())
                .isEqualTo("市场-玉米-退回记录修正表.xlsx");
        byte[] workbook = response.getContentAsByteArray();
        assertThat(BusinessImportWorkbook.purpose(workbook))
                .isEqualTo(MarketReturnedCorrectionWorkbook.PURPOSE);

        List<List<String>> rows = XlsxTable.parseWorksheet(workbook, 1, 256).stream()
                .map(MarketReturnedCorrectionRestIntegrationTest::withoutTrailingBlanks)
                .toList();
        assertThat(rows).hasSize(3);
        assertThat(rows.getFirst()).startsWith("原单编号（请勿修改）", "原单版本（系统校验）")
                .contains("地区", "纬度（度）", "经度（度）")
                .doesNotContain(BusinessImportWorkbook.PHOTO_FILENAMES_LABEL, "填报人", "具体品种", "库存权属");
        assertThat(rows.subList(1, rows.size())).extracting(row -> row.getFirst())
                .containsExactlyInAnyOrder(first, second)
                .doesNotContain(otherReason, soybean, unauthorized);
        int regionColumn = rows.getFirst().indexOf("地区");
        assertThat(rows.subList(1, rows.size()))
                .allSatisfy(row -> assertThat(row.get(regionColumn)).contains("齐齐哈尔市"));

        assertThat(count("market.market_record")).isEqualTo(recordCount);
        assertThat(count("platform.business_audit_event")).isEqualTo(auditCount);
        assertThat(count("platform.business_import_draft")).isEqualTo(draftCount);
        assertThat(recordStateVersions()).isEqualTo(stateVersions);
    }

    @Test
    @EnabledIfSystemProperty(named = "market.correction.office.roundtrip.dir", matches = ".+")
    void acceptsTheDownloadedWorkbookAfterAnExternalOfficeRoundTrip() throws Exception {
        String id = returned("CORN", "TRADER", "230200", "Office往返样本", COORDINATE_REASON);
        long recordCount = count("market.market_record");
        byte[] downloaded = correctionWorkbook("CORN");
        Path gateDirectory = Path.of(System.getProperty("market.correction.office.roundtrip.dir"));
        Files.createDirectories(gateDirectory);
        Path downloadedPath = gateDirectory.resolve("下载原表.xlsx");
        Path roundTrippedPath = gateDirectory.resolve("Office另存表.xlsx");
        Files.deleteIfExists(roundTrippedPath);
        Files.write(downloadedPath, downloaded);

        for (int attempt = 0; attempt < 240 && !Files.isRegularFile(roundTrippedPath); attempt++) {
            Thread.sleep(500);
        }
        assertThat(roundTrippedPath)
                .withFailMessage("等待外部 Office 另存结果超时: %s", roundTrippedPath)
                .isRegularFile();

        byte[] roundTripped = Files.readAllBytes(roundTrippedPath);
        assertThat(BusinessImportWorkbook.purpose(roundTripped))
                .isEqualTo(MarketReturnedCorrectionWorkbook.PURPOSE);
        assertThat(withoutTrailingBlanks(
                        XlsxTable.parseWorksheet(roundTripped, 1, 256).get(1)))
                .startsWith(id, "2");

        String completed = upload(
                roundTripped, "returned-correction-office-roundtrip", status().isCreated());
        assertThat(completed).contains("\"statusCode\":\"COMPLETED\"");
        assertThat(jdbc.sql("""
                SELECT status_code || ':' || version FROM market.market_record WHERE record_id=:id
                """).param("id", id).query(String.class).single()).isEqualTo("PENDING_REVIEW:4");
        assertThat(count("market.market_record")).isEqualTo(recordCount);
        assertThat(count("platform.business_import_draft")).isZero();
        assertThat(jdbc.sql("""
                SELECT count(*) FROM market.market_record WHERE status_code='VOIDED'
                """).query(Long.class).single()).isZero();
    }

    @Test
    void rejectsEmptyOrMismatchedDownloadContext() throws Exception {
        returned("SOYBEAN", "TRADER", "230200", "只有大豆退回", COORDINATE_REASON);

        mvc.perform(get("/api/v1/imports/market/returned-corrections/template")
                        .principal(() -> "market-tester"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/imports/market/returned-corrections/template")
                        .param("productCode", "UNKNOWN")
                        .principal(() -> "market-tester"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/imports/market/returned-corrections/template")
                        .param("productCode", "CORN")
                        .principal(() -> "market-tester"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MARKET_RETURNED_CORRECTION_EMPTY"))
                .andExpect(jsonPath("$.error.message").value(
                        "当前品种没有可批量修正的地区与经纬度不匹配退回记录"));

        assertThat(count("market.market_record")).isOne();
        assertThat(count("platform.import_job")).isZero();
        assertThat(count("platform.business_import_draft")).isZero();
    }

    @Test
    void updatesTheOriginalReturnedRecordAndResubmitsWithoutCreatingADuplicate() throws Exception {
        String id = returned("CORN", "TRADER", "230200", "待修正样本", COORDINATE_REASON);
        long recordCount = count("market.market_record");
        long photoCount = jdbc.sql("""
                SELECT count(*) FROM evidence.evidence_photo
                WHERE attached_domain='MARKET' AND attached_record_id=:id
                """).param("id", id).query(Long.class).single();

        String correctedId = subjects.callAs("market-tester", () -> rowService.correctAndSubmit(
                id, 2, correctionRow(
                        "CORN", "TRADER", "230200", "已修正样本", "47.5000000", "123.5000000")));

        assertThat(correctedId).isEqualTo(id);
        assertThat(count("market.market_record")).isEqualTo(recordCount);
        assertThat(jdbc.sql("""
                SELECT status_code || ':' || version FROM market.market_record WHERE record_id=:id
                """).param("id", id).query(String.class).single()).isEqualTo("PENDING_REVIEW:4");
        assertThat(jdbc.sql("""
                SELECT string_agg(field_code || ':' || value,',' ORDER BY field_code)
                FROM market.market_record_core_value
                WHERE record_id=:id AND field_code IN (
                  'MKT_SAMPLE_NAME','MKT_SAMPLE_LATITUDE','MKT_SAMPLE_LONGITUDE')
                """).param("id", id).query(String.class).single())
                .isEqualTo("MKT_SAMPLE_LATITUDE:47.5000000,MKT_SAMPLE_LONGITUDE:123.5000000,MKT_SAMPLE_NAME:已修正样本");
        assertThat(jdbc.sql("""
                SELECT count(*) FROM evidence.evidence_photo
                WHERE attached_domain='MARKET' AND attached_record_id=:id
                """).param("id", id).query(Long.class).single()).isEqualTo(photoCount);
        assertThat(count("platform.business_import_draft")).isZero();
    }

    @Test
    void keepsFailedRowsReturnedAndRollsBackEachRowIndependently() throws Exception {
        String valid = returned("CORN", "TRADER", "230200", "合法修正样本", COORDINATE_REASON);
        String invalid = returned("CORN", "TRADER", "230200", "非法修正样本", COORDINATE_REASON);
        String invalidBefore = recordSnapshot(invalid);

        subjects.callAs("market-tester", () -> rowService.correctAndSubmit(
                valid, 2, correctionRow(
                        "CORN", "TRADER", "230200", "合法修正样本", "47.6000000", "123.6000000")));
        assertThatThrownBy(() -> subjects.callAs("market-tester", () -> rowService.correctAndSubmit(
                invalid, 2, correctionRow(
                        "CORN", "TRADER", "230200", "非法修正样本", "60.0000000", "150.0000000"))))
                .isInstanceOfSatisfying(ClientRequestException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("MARKET_SAMPLE_POINT_OUTSIDE_REGION");
                    assertThat(exception.clientMessage()).isEqualTo(
                            "样本点经纬度不在所选地区范围内，请核对后重新上传");
                });

        assertThat(recordSnapshot(valid)).contains("PENDING_REVIEW:4:47.6000000:123.6000000");
        assertThat(recordSnapshot(invalid)).isEqualTo(invalidBefore);
        assertThat(count("market.market_record")).isEqualTo(2);
        assertThat(count("platform.business_import_draft")).isZero();
    }

    @Test
    void acceptsAPointCoveredByADescendantBoundaryOfTheSelectedRegion() throws Exception {
        String id = returned(
                "CORN", "TRADER", "230200", "区县下级边界样本", COORDINATE_REASON);
        boundary("230200", 0, 0, 1, 1);
        boundary("230202", 122, 46, 125, 49);
        boundary("230208", 0, 0, 1, 1);
        long recordCount = count("market.market_record");

        String correctedId = subjects.callAs("market-tester", () -> rowService.correctAndSubmit(
                id, 2, correctionRow(
                        "CORN", "TRADER", "230200", "区县下级边界样本", "47.2991350", "123.9270770")));

        assertThat(correctedId).isEqualTo(id);
        assertThat(recordSnapshot(id)).contains("PENDING_REVIEW:4:47.2991350:123.9270770");
        assertThat(count("market.market_record")).isEqualTo(recordCount);
        assertThat(count("platform.business_import_draft")).isZero();

        mvc.perform(post("/api/v1/market-records/{id}/approve", id)
                        .principal(() -> "production-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(id))
                .andExpect(jsonPath("$.data.status").value("APPROVED"));

        assertThat(jdbc.sql("""
                SELECT point.region_code
                FROM market.market_record record
                JOIN registry.sample_point point ON point.sample_point_id=record.sample_point_id
                WHERE record.record_id=:id
                """).param("id", id).query(String.class).single()).isEqualTo("230202");
    }

    @Test
    void rejectsStateVersionProductIdentifierAndRegionWithoutMutatingRecords() throws Exception {
        String wrongState = returned("CORN", "TRADER", "230200", "状态变化样本", COORDINATE_REASON);
        mvc.perform(post("/api/v1/market-records/{id}/submit", wrongState)
                        .principal(() -> "market-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":2}"))
                .andExpect(status().isOk());
        String stale = returned("CORN", "TRADER", "230200", "版本变化样本", COORDINATE_REASON);
        String wrongProduct = returned("CORN", "TRADER", "230200", "品种篡改样本", COORDINATE_REASON);
        String unauthorized = returned("CORN", "TRADER", "231100", "越权修正样本", COORDINATE_REASON);
        restrictMarketTesterTo("230200");
        Map<String, String> before = snapshots(wrongState, stale, wrongProduct, unauthorized);

        assertThatThrownBy(() -> subjects.callAs("market-tester", () -> rowService.correctAndSubmit(
                wrongState, 3, correctionRow(
                        "CORN", "TRADER", "230200", "状态变化样本", "47.5", "123.5"))))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> subjects.callAs("market-tester", () -> rowService.correctAndSubmit(
                stale, 1, correctionRow(
                        "CORN", "TRADER", "230200", "版本变化样本", "47.5", "123.5"))))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> subjects.callAs("market-tester", () -> rowService.correctAndSubmit(
                wrongProduct, 2, correctionRow(
                        "SOYBEAN", "TRADER", "230200", "品种篡改样本", "47.5", "123.5"))))
                .isInstanceOf(ClientRequestException.class);
        assertThatThrownBy(() -> subjects.callAs("market-tester", () -> rowService.correctAndSubmit(
                UUID.randomUUID().toString(), 2, correctionRow(
                        "CORN", "TRADER", "230200", "编号篡改样本", "47.5", "123.5"))))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> subjects.callAs("market-tester", () -> rowService.correctAndSubmit(
                unauthorized, 2, correctionRow(
                        "CORN", "TRADER", "231100", "越权修正样本", "49.5", "126.5"))))
                .isInstanceOf(com.cofco.qiqihar.graintrade.shared.application.AccessDeniedException.class);

        assertThat(snapshots(wrongState, stale, wrongProduct, unauthorized)).isEqualTo(before);
        assertThat(count("market.market_record")).isEqualTo(4);
        assertThat(count("platform.business_import_draft")).isZero();
    }

    @Test
    void keepsCorrectionUploadsIdempotentAndIsolatesTheirJobEndpoints() throws Exception {
        returned("CORN", "TRADER", "230200", "幂等修正样本", COORDINATE_REASON);
        byte[] workbook = correctionWorkbook("CORN");

        String first = upload(workbook, "returned-correction-idempotent", status().isCreated());
        UUID correctionJobId = jobId(first);
        String repeated = upload(workbook, "returned-correction-idempotent", status().isCreated());
        assertThat(jobId(repeated)).isEqualTo(correctionJobId);

        returned("CORN", "TRADER", "230200", "不同内容修正样本", COORDINATE_REASON);
        byte[] differentWorkbook = correctionWorkbook("CORN");
        mvc.perform(multipart("/api/v1/imports/market/returned-corrections")
                        .file(correctionFile(differentWorkbook)).param("productCode", "CORN")
                        .header("Idempotency-Key", "returned-correction-idempotent")
                        .principal(() -> "market-tester"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IMPORT_IDEMPOTENCY_KEY_CONFLICT"));

        UUID ordinaryJobId = ordinaryMarketJob();
        mvc.perform(get("/api/v1/imports/market/returned-corrections/{id}", ordinaryJobId)
                        .principal(() -> "market-tester"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/imports/market/returned-corrections/{id}/errors", ordinaryJobId)
                        .principal(() -> "market-tester"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/imports/market/{id}", correctionJobId)
                        .principal(() -> "market-tester"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/imports/market/{id}/errors", correctionJobId)
                        .principal(() -> "market-tester"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsANewKeyDuplicateWithoutChangingTheAlreadySubmittedOriginal() throws Exception {
        String id = returned("CORN", "TRADER", "230200", "重复上传样本", COORDINATE_REASON);
        byte[] workbook = correctionWorkbook("CORN");
        long recordCount = count("market.market_record");

        String first = upload(workbook, "returned-correction-first", status().isCreated());
        assertThat(first).contains("\"statusCode\":\"COMPLETED\"");
        long submissionEvents = jdbc.sql("""
                SELECT count(*) FROM platform.business_event_outbox
                WHERE aggregate_type='MARKET_RECORD' AND aggregate_id=:id
                  AND action_code='MARKET_RECORD_SUBMITTED'
                """).param("id", id).query(Long.class).single();

        String duplicate = upload(workbook, "returned-correction-second", status().isCreated());
        UUID duplicateJobId = jobId(duplicate);
        assertThat(duplicate).contains("\"statusCode\":\"COMPLETED_WITH_ERRORS\"")
                .contains("\"failedRows\":1");
        String errors = mvc.perform(get(
                                "/api/v1/imports/market/returned-corrections/{id}/errors", duplicateJobId)
                        .principal(() -> "market-tester"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(errors)
                .startsWith("原单编号,工作表行号,失败原因")
                .contains(id, "原记录已不是可修正的退回状态")
                .doesNotContain("errorCode", "MARKET_RETURNED_CORRECTION_STATE_CONFLICT", "Exception");

        assertThat(recordSnapshot(id)).contains("PENDING_REVIEW:4:");
        assertThat(count("market.market_record")).isEqualTo(recordCount);
        assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.business_event_outbox
                WHERE aggregate_type='MARKET_RECORD' AND aggregate_id=:id
                  AND action_code='MARKET_RECORD_SUBMITTED'
                """).param("id", id).query(Long.class).single()).isEqualTo(submissionEvents);
        assertThat(count("platform.business_import_draft")).isZero();
    }

    @Test
    void keepsTheCorrectedOriginalUnpublishedUntilApprovalThenRefreshesApprovedConsumers()
            throws Exception {
        String id = returned(
                "CORN", "TRADER", "230200", "同原单发布链样本", COORDINATE_REASON);
        long recordCount = count("market.market_record");
        byte[] workbook = correctionWorkbook("CORN");

        String completed = upload(workbook, "returned-correction-publication", status().isCreated());
        assertThat(completed).contains("\"statusCode\":\"COMPLETED\"");
        assertThat(jdbc.sql("""
                SELECT status_code || ':' || version FROM market.market_record WHERE record_id=:id
                """).param("id", id).query(String.class).single()).isEqualTo("PENDING_REVIEW:4");

        mvc.perform(get("/api/v1/observable-analysis/snapshots")
                        .principal(() -> "production-tester")
                        .queryParam("productCode", "CORN").queryParam("regionCode", "230200")
                        .queryParam("surveyYear", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.coverage.pendingReviewRecordCount").value(1))
                .andExpect(jsonPath("$.data.market.metrics[?(@.code == 'PURCHASE_VOLUME')]").isEmpty())
                .andExpect(jsonPath("$.data.supply.inventory.enterpriseEndingTonnes").value((Object) null))
                .andExpect(jsonPath("$.data.supply.inventory.adoptedRecordCount").value(0));

        mvc.perform(post("/api/v1/market-records/{id}/approve", id)
                        .principal(() -> "production-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(id))
                .andExpect(jsonPath("$.data.status").value("APPROVED"));

        mvc.perform(get("/api/v1/market-records/{id}", id)
                        .principal(() -> "production-tester"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(id))
                .andExpect(jsonPath("$.data.status").value("APPROVED"));
        assertThat(jdbc.sql("""
                WITH RECURSIVE scope(code) AS (
                  SELECT code FROM platform.region WHERE code='230200'
                  UNION ALL
                  SELECT child.code FROM platform.region child JOIN scope parent
                    ON child.parent_code=parent.code)
                SELECT count(*) FROM market.market_record record
                LEFT JOIN registry.current_sample_subject_resolution resolution
                  ON resolution.source_domain='MARKET'
                 AND resolution.source_record_id=record.record_id
                JOIN registry.sample_point point ON point.sample_point_id=COALESCE(
                  resolution.target_sample_point_id,record.sample_point_id)
                JOIN platform.monitoring_scope_region member
                  ON member.scope_code='FORMAL_BUSINESS'
                 AND member.region_code=point.region_code AND member.included
                JOIN overview.administrative_boundary boundary
                  ON boundary.region_code=point.region_code
                 AND boundary.geometry_sha256=point.containment_boundary_sha256
                 AND boundary.source_revision=point.containment_boundary_revision
                JOIN market.market_record_business_identity identity
                  ON identity.record_id=record.record_id
                WHERE record.record_id=:id AND record.status_code='APPROVED'
                  AND record.survey_period_governance_state='CONFIRMED'
                  AND point.approval_state='APPROVED' AND point.location_state='VALID'
                  AND point.region_code IN(SELECT code FROM scope)
                  AND ST_Covers(boundary.geometry,point.governed_point)
                """).param("id", id).query(Long.class).single()).isOne();
        mvc.perform(get("/api/v1/observable-analysis/snapshots")
                        .principal(() -> "production-tester")
                        .queryParam("productCode", "CORN").queryParam("regionCode", "230200")
                        .queryParam("surveyYear", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.coverage.pendingReviewRecordCount").value(0))
                .andExpect(jsonPath("$.data.market.metrics[?(@.code == 'PURCHASE_VOLUME')].value")
                        .value(org.hamcrest.Matchers.hasItem("12.0000")))
                .andExpect(jsonPath("$.data.market.metrics[?(@.code == 'PURCHASE_VOLUME')].sourceCount")
                        .value(org.hamcrest.Matchers.hasItem(1)))
                .andExpect(jsonPath("$.data.supply.inventory.enterpriseEndingTonnes").value("12.0000"))
                .andExpect(jsonPath("$.data.supply.inventory.adoptedRecordCount").value(1));
        mvc.perform(get("/api/v1/overview/dashboard")
                        .principal(() -> "production-tester")
                        .queryParam("productCode", "CORN").queryParam("regionCode", "230200")
                        .queryParam("year", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.metrics[?(@.code == 'MARKET_AVERAGE_PURCHASE_PRICE')].value")
                        .value(org.hamcrest.Matchers.hasItem("2300")))
                .andExpect(jsonPath("$.data.metrics[?(@.code =~ /SUPPLY_.*/)]").isEmpty())
                .andExpect(jsonPath("$.data.metrics[?(@.code == 'REGION_SURPLUS')]").isEmpty());

        assertThat(count("market.market_record")).isEqualTo(recordCount);
        assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.business_event_outbox
                WHERE aggregate_type='MARKET_RECORD' AND aggregate_id=:id
                  AND action_code='MARKET_RECORD_APPROVED'
                """).param("id", id).query(Long.class).single()).isOne();
        assertThat(count("platform.business_import_draft")).isZero();
    }

    @Test
    void correctsCornSoybeanAndRiceThroughTheirOwnOriginalRecordWorkbooks()
            throws Exception {
        Map<String, String> originals = Map.of(
                "CORN", returned("CORN", "TRADER", "230200", "玉米修正样本", COORDINATE_REASON),
                "SOYBEAN", returned("SOYBEAN", "TRADER", "230200", "大豆修正样本", COORDINATE_REASON),
                "RICE", returned("RICE", "TRADER", "230200", "稻谷修正样本", COORDINATE_REASON));
        long recordCount = count("market.market_record");

        for (Map.Entry<String, String> entry : originals.entrySet()) {
            String result = upload(correctionWorkbook(entry.getKey()),
                    "three-product-correction-" + entry.getKey(), entry.getKey(), status().isCreated());
            assertThat(result).contains("\"statusCode\":\"COMPLETED\"");
            assertThat(jdbc.sql("""
                    SELECT status_code || ':' || version
                    FROM market.market_record WHERE record_id=:id
                    """).param("id", entry.getValue()).query(String.class).single())
                    .isEqualTo("PENDING_REVIEW:4");
        }

        assertThat(count("market.market_record")).isEqualTo(recordCount);
        assertThat(count("platform.business_import_draft")).isZero();
        assertThat(jdbc.sql("""
                SELECT count(*) FROM market.market_record WHERE status_code='VOIDED'
                """).query(Long.class).single()).isZero();
    }

    @Test
    void rejectsOrdinaryAndCorrectionWorkbooksAtTheWrongUploadEndpoint() throws Exception {
        returned("CORN", "TRADER", "230200", "模板入口隔离样本", COORDINATE_REASON);
        byte[] correction = correctionWorkbook("CORN");
        byte[] ordinary = mvc.perform(get("/api/v1/imports/market/template")
                        .param("format", "xlsx").param("productCode", "CORN")
                        .principal(() -> "market-tester"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        long jobsBefore = count("platform.import_job");

        mvc.perform(multipart("/api/v1/imports/market")
                        .file(new MockMultipartFile("file", "普通导入.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                correction))
                        .param("productCode", "CORN")
                        .header("Idempotency-Key", "correction-at-ordinary-endpoint")
                        .principal(() -> "market-tester"))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/v1/imports/market/returned-corrections")
                        .file(new MockMultipartFile("file", "退回记录修正表.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                ordinary))
                        .param("productCode", "CORN")
                        .header("Idempotency-Key", "ordinary-at-correction-endpoint")
                        .principal(() -> "market-tester"))
                .andExpect(status().isBadRequest());

        assertThat(count("platform.import_job")).isEqualTo(jobsBefore);
        assertThat(count("market.market_record")).isOne();
        assertThat(count("platform.business_import_draft")).isZero();
    }

    private String returned(
            String product, String objectType, String region, String sampleName, String reason)
            throws Exception {
        String quality = "SOYBEAN".equals(product) ? "PROTEIN" : "MOISTURE";
        UUID photo = stagePhoto();
        String body = """
                {"productCode":"%s","coreValues":{
                 "MKT_OBJECT_TYPE":"%s","MKT_REGION":"%s","MKT_TRADE_DATE":"2026-08-01",
                 "MKT_PURCHASE_BASE_PRICE":"2300"%s,
                 "MKT_CARRIAGE_BOARD_AMOUNT":"36","MKT_PACKAGING_AMOUNT":"12",
                 "MKT_FREIGHT_AMOUNT":"72","MKT_PACKAGING_FORM":"BULK",
                 "MKT_SAMPLE_NAME":"%s","MKT_SURVEYOR_NAME":"王雷",
                 "MKT_SURVEYOR_PHONE":"13800000000","MKT_SAMPLE_CONTACT":"13900000000",
                 "MKT_SAMPLE_LATITUDE":"47.3543","MKT_SAMPLE_LONGITUDE":"123.9182"},
                 "facts":{"PURCHASE_VOLUME":"12","ENDING_INVENTORY":"12","%s":"14.6"},
                 "evidencePhotoIds":["%s"]}
                """.formatted(product, objectType, region,
                "TRADER".equals(objectType) ? ",\"MKT_SALE_BASE_PRICE\":\"2300\"" : "",
                sampleName, quality, photo);
        String id = mvc.perform(post("/api/v1/market-records")
                        .principal(() -> "market-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()
                .replaceFirst("(?s).*?\\\"id\\\":\\\"([^\\\"]+)\\\".*", "$1");
        mvc.perform(post("/api/v1/market-records/{id}/submit", id)
                        .principal(() -> "market-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/market-records/{id}/return", id)
                        .principal(() -> "production-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"reason\":\"" + reason + "\"}"))
                .andExpect(status().isOk());
        return id;
    }

    private UUID stagePhoto() {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO evidence.evidence_photo(photo_id,state_code,original_filename,media_type,
                  original_bytes,watermarked_bytes,byte_length,sha256,captured_at,capture_latitude,
                  capture_longitude,watermark_text,uploaded_by,uploaded_at)
                VALUES(:id,'STAGED','修正下载验证.png','image/png',decode('00','hex'),decode('01','hex'),
                  1,encode(sha256(decode('00','hex')),'hex'),now(),47.3543,123.9182,
                  '市场采集','market-tester',now())
                """).param("id", id).update();
        return id;
    }

    private byte[] correctionWorkbook(String productCode) throws Exception {
        return mvc.perform(get("/api/v1/imports/market/returned-corrections/template")
                        .param("productCode", productCode).principal(() -> "market-tester"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
    }

    private String upload(byte[] workbook, String key,
            org.springframework.test.web.servlet.ResultMatcher expectedStatus) throws Exception {
        return upload(workbook, key, "CORN", expectedStatus);
    }

    private String upload(byte[] workbook, String key, String productCode,
            org.springframework.test.web.servlet.ResultMatcher expectedStatus) throws Exception {
        return mvc.perform(multipart("/api/v1/imports/market/returned-corrections")
                        .file(correctionFile(workbook)).param("productCode", productCode)
                        .header("Idempotency-Key", key).principal(() -> "market-tester"))
                .andExpect(expectedStatus).andReturn().getResponse().getContentAsString();
    }

    private static MockMultipartFile correctionFile(byte[] workbook) {
        return new MockMultipartFile("file", "市场-玉米-退回记录修正表.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", workbook);
    }

    private UUID ordinaryMarketJob() {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO platform.import_job(import_job_id,domain_code,idempotency_key,content_sha256,
                  source_content,requested_by,work_unit_code,status_code,created_at,completed_at)
                SELECT :id,'MARKET',:key,repeat('a',64),'GOVERNED-DRAFT-V1:ordinary',
                  subject_id,work_unit_code,'COMPLETED',now(),now()
                FROM platform.security_user WHERE subject_id='market-tester'
                """).param("id", id).param("key", "ordinary-market-" + id).update();
        return id;
    }

    private static UUID jobId(String body) {
        return UUID.fromString(body.replaceFirst("(?s).*?\"id\":\"([^\"]+)\".*", "$1"));
    }

    private MarketImportRow correctionRow(
            String product, String objectType, String region, String sampleName,
            String latitude, String longitude) {
        Map<String, String> core = new LinkedHashMap<>();
        core.put("MKT_OBJECT_TYPE", objectType);
        core.put("MKT_REGION", region);
        core.put("MKT_TRADE_DATE", "2026-08-01");
        core.put("MKT_PURCHASE_BASE_PRICE", "2300");
        if ("TRADER".equals(objectType)) core.put("MKT_SALE_BASE_PRICE", "2300");
        core.put("MKT_CARRIAGE_BOARD_AMOUNT", "36");
        core.put("MKT_PACKAGING_AMOUNT", "12");
        core.put("MKT_FREIGHT_AMOUNT", "72");
        core.put("MKT_PACKAGING_FORM", "BULK");
        core.put("MKT_SAMPLE_NAME", sampleName);
        core.put("MKT_SURVEYOR_NAME", "王雷");
        core.put("MKT_SURVEYOR_PHONE", "13800000000");
        core.put("MKT_SAMPLE_CONTACT", "13900000000");
        core.put("MKT_SAMPLE_LATITUDE", latitude);
        core.put("MKT_SAMPLE_LONGITUDE", longitude);
        String quality = "SOYBEAN".equals(product) ? "PROTEIN" : "MOISTURE";
        return new MarketImportRow(product, core,
                Map.of("PURCHASE_VOLUME", new BigDecimal("12"), quality, new BigDecimal("14.6")),
                List.of());
    }

    private void boundary(String regionCode, int west, int south, int east, int north) {
        jdbc.sql("""
                INSERT INTO overview.administrative_boundary(
                  region_code,geometry,source_name,source_url,source_revision,source_license,
                  source_feature_id,source_effective_on,geometry_sha256)
                VALUES(:regionCode,ST_Multi(ST_MakeEnvelope(:west,:south,:east,:north,4326)),
                  '退回市场修正验证','urn:test:returned-market-correction','test-v1',
                  'Test fixture',:regionCode,DATE '2026-08-19',repeat('6',64))
                ON CONFLICT(region_code) DO UPDATE SET
                  geometry=EXCLUDED.geometry,source_name=EXCLUDED.source_name,
                  source_url=EXCLUDED.source_url,source_revision=EXCLUDED.source_revision,
                  source_license=EXCLUDED.source_license,source_feature_id=EXCLUDED.source_feature_id,
                  source_effective_on=EXCLUDED.source_effective_on,
                  geometry_sha256=EXCLUDED.geometry_sha256
                """).param("regionCode", regionCode).param("west", west).param("south", south)
                .param("east", east).param("north", north).update();
    }

    private void restrictMarketTesterTo(String regionCode) {
        jdbc.sql("DELETE FROM platform.security_user_region_scope WHERE subject_id='market-tester'").update();
        jdbc.sql("""
                INSERT INTO platform.security_user_region_scope(subject_id,region_code)
                VALUES('market-tester',:regionCode)
                """).param("regionCode", regionCode).update();
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private String recordStateVersions() {
        return jdbc.sql("""
                SELECT string_agg(record_id || ':' || status_code || ':' || version,',' ORDER BY record_id)
                FROM market.market_record
                """).query(String.class).single();
    }

    private String recordSnapshot(String id) {
        return jdbc.sql("""
                SELECT record.status_code || ':' || record.version || ':' ||
                  latitude.value || ':' || longitude.value
                FROM market.market_record record
                JOIN market.market_record_core_value latitude
                  ON latitude.record_id=record.record_id AND latitude.field_code='MKT_SAMPLE_LATITUDE'
                JOIN market.market_record_core_value longitude
                  ON longitude.record_id=record.record_id AND longitude.field_code='MKT_SAMPLE_LONGITUDE'
                WHERE record.record_id=:id
                """).param("id", id).query(String.class).single();
    }

    private Map<String, String> snapshots(String... ids) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String id : ids) values.put(id, recordSnapshot(id));
        return Map.copyOf(values);
    }

    private static List<String> withoutTrailingBlanks(List<String> values) {
        int size = values.size();
        while (size > 0 && values.get(size - 1).isBlank()) size--;
        return List.copyOf(values.subList(0, size));
    }
}
