package com.cofco.qiqihar.graintrade.importing.interfaceadapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.importing.application.MarketImportTemplate;
import com.cofco.qiqihar.graintrade.importing.infrastructure.BusinessImportWorkbook;
import com.cofco.qiqihar.graintrade.market.importing.MarketImportPort;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
class MarketImportRestIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired DataSource dataSource;
    @Autowired MarketImportPort market;
    private JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("""
                TRUNCATE platform.import_row_result,platform.import_job,platform.business_audit_event,
                  market.market_record,evidence.evidence_photo,registry.sample_point
                  RESTART IDENTITY CASCADE
                """).update();
        jdbc.sql("""
                INSERT INTO overview.administrative_boundary(
                  region_code,geometry,source_name,source_url,source_revision,source_license,
                  source_feature_id,source_effective_on,geometry_sha256)
                VALUES('230200',ST_Multi(ST_MakeEnvelope(122,46,125,49,4326)),
                  'market import sample-point fixture','urn:test:market-import-sample-point','test-v1',
                  'Test fixture','230200',DATE '2026-08-18',repeat('8',64))
                ON CONFLICT (region_code) DO UPDATE SET
                  geometry=EXCLUDED.geometry,source_name=EXCLUDED.source_name,
                  source_url=EXCLUDED.source_url,source_revision=EXCLUDED.source_revision,
                  source_license=EXCLUDED.source_license,source_feature_id=EXCLUDED.source_feature_id,
                  source_effective_on=EXCLUDED.source_effective_on,
                  geometry_sha256=EXCLUDED.geometry_sha256
                """).update();
    }

    @AfterEach
    void clean() {
        jdbc.sql("TRUNCATE platform.import_row_result,platform.import_job,platform.business_audit_event,market.market_record,evidence.evidence_photo,registry.sample_point RESTART IDENTITY CASCADE").update();
    }

    @Test
    void importsAnOwnedMarketEvidenceRowAtomicallyAndLocksTheReporterToTheAuthenticatedEmployee() throws Exception {
        String photoId = uploadEvidence();
        String csv = String.join(",", MarketImportTemplate.HEADERS) + "\n" + row(photoId, "14.6");

        mvc.perform(multipart("/api/v1/imports/market")
                        .file(new MockMultipartFile("file", "market.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
                        .param("productCode", "CORN").param("objectTypeCode", "FEED_MILL")
                        .header("Idempotency-Key", "market-import-1").principal(() -> "market-tester"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.domainCode").value("MARKET"))
                .andExpect(jsonPath("$.data.importedRows").value(1))
                .andExpect(jsonPath("$.data.failedRows").value(0));

        assertThat(jdbc.sql("""
                        SELECT value FROM market.market_record_core_value
                        WHERE field_code='MKT_REPORTER_NAME'
                        """).query(String.class).single())
                .isEqualTo("市场测试员");
        assertThat(jdbc.sql("""
                        SELECT value FROM market.market_record_core_value
                        WHERE field_code='MKT_SURVEYOR_PHONE'
                        """).query(String.class).single())
                .isEqualTo("13800000000");
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM market.market_record_core_value
                        WHERE field_code='MKT_REPORTER_PHONE'
                        """).query(Long.class).single())
                .isZero();
        assertThat(jdbc.sql("SELECT attached_domain FROM evidence.evidence_photo WHERE photo_id=CAST(:id AS uuid)")
                        .param("id", photoId).query(String.class).single())
                .isEqualTo("MARKET");
    }

    @Test
    void publishesTheServerTemplateAndReplaysTheSameIdempotentJob() throws Exception {
        mvc.perform(get("/api/v1/imports/market/template").principal(() -> "market-tester"))
                .andExpect(status().isOk())
                .andExpect(content().string(MarketImportTemplate.csv()));

        String photoId = uploadEvidence();
        byte[] csv = (String.join(",", MarketImportTemplate.HEADERS) + "\n" + row(photoId, "14.6"))
                .getBytes(StandardCharsets.UTF_8);
        String first = mvc.perform(multipart("/api/v1/imports/market")
                        .file(new MockMultipartFile("file", "market.csv", "text/csv", csv))
                        .param("productCode", "CORN").param("objectTypeCode", "FEED_MILL")
                        .header("Idempotency-Key", "market-import-idempotent").principal(() -> "market-tester"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String second = mvc.perform(multipart("/api/v1/imports/market")
                        .file(new MockMultipartFile("file", "market.csv", "text/csv", csv))
                        .param("productCode", "CORN").param("objectTypeCode", "FEED_MILL")
                        .header("Idempotency-Key", "market-import-idempotent").principal(() -> "market-tester"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();

        assertThat(first.replaceFirst("(?s).*?\"id\":\"([^\"]+)\".*", "$1"))
                .isEqualTo(second.replaceFirst("(?s).*?\"id\":\"([^\"]+)\".*", "$1"));
        assertThat(jdbc.sql("SELECT count(*) FROM market.market_record").query(Long.class).single()).isOne();
    }

    @Test
    void importsTheSameServerOwnedTemplateFromXlsx() throws Exception {
        String photoId = uploadEvidence();
        byte[] downloaded = mvc.perform(get("/api/v1/imports/market/template")
                        .param("format", "xlsx")
                        .param("productCode", "CORN")
                        .param("objectTypeCode", "FEED_MILL")
                        .principal(() -> "market-tester"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        var template = internalTemplate("CORN", "FEED_MILL");
        java.util.Map<String, String> fields = new java.util.HashMap<>();
        fields.put("surveyYear", "2026");
        fields.put("surveyMonth", "8");
        fields.put("MKT_REGION", "230200");
        fields.put("MKT_TRADE_DATE", "2026-08-01");
        fields.put("MKT_PURCHASE_BASE_PRICE", "2300");
        fields.put("MKT_SALE_BASE_PRICE", "2380");
        fields.put("MKT_CARRIAGE_BOARD_AMOUNT", "36");
        fields.put("MKT_PACKAGING_AMOUNT", "12");
        fields.put("MKT_FREIGHT_AMOUNT", "72");
        fields.put("MKT_PACKAGING_FORM", "BULK");
        fields.put("MKT_SURVEYOR_NAME", "王雷");
        fields.put("MKT_SURVEYOR_PHONE", "13800000000");
        fields.put("MKT_SAMPLE_NAME", "齐齐哈尔第一粮店");
        fields.put("MKT_SAMPLE_CONTACT", "13900000000");
        fields.put("MKT_SAMPLE_LATITUDE", "47.3543");
        fields.put("MKT_SAMPLE_LONGITUDE", "123.9182");
        fields.put("PURCHASE_VOLUME", "12");
        fields.put("MOISTURE", "14.6");
        fields.put("ENDING_INVENTORY", "20");
        fields.put(MarketImportTemplate.EVIDENCE_PHOTO_ID, photoId);
        List<String> values = template.headers().stream()
                .map(header -> fields.getOrDefault(header, "")).toList();
        byte[] workbook = BusinessImportWorkbook.create(
                template, List.of(values));

        String importResponse = mvc.perform(multipart("/api/v1/imports/market")
                        .file(new MockMultipartFile("file", "market.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                workbook))
                        .param("productCode", "CORN").param("objectTypeCode", "FEED_MILL")
                        .header("Idempotency-Key", "market-xlsx-1").principal(() -> "market-tester"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.importedRows").value(1))
                .andReturn().getResponse().getContentAsString();
        String importJobId = importResponse.replaceFirst(
                "(?s).*?\\\"id\\\":\\\"([^\\\"]+)\\\".*", "$1");

        mvc.perform(get("/api/v1/imports/market")
                        .param("pageNumber", "0").param("pageSize", "5")
                        .principal(() -> "market-tester"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(importJobId))
                .andExpect(jsonPath("$.data.items[0].domainCode").value("MARKET"))
                .andExpect(jsonPath("$.data.items[0].importedRows").value(1))
                .andExpect(jsonPath("$.data.items[0].failedRows").value(0));

        mvc.perform(get("/api/v1/imports/market")
                        .param("pageNumber", "0").param("pageSize", "5")
                        .principal(() -> "market-tester"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(importJobId));

        mvc.perform(get("/api/v1/market-records")
                        .queryParam("productCode", "CORN")
                        .queryParam("pageKind", "MONITORING")
                        .queryParam("pageNumber", "0")
                        .queryParam("pageSize", "20")
                        .principal(() -> "market-tester"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].values.MKT_STORAGE_REGION_CODE").doesNotExist());
    }

    @Test
    void importsPublicInventoryFieldsDirectlyIntoPendingReviewWithoutHiddenContracts() throws Exception {
        byte[] downloaded = mvc.perform(get("/api/v1/imports/market/template")
                        .param("format", "xlsx")
                        .param("productCode", "CORN")
                        .param("objectTypeCode", "FEED_MILL")
                        .principal(() -> "market-tester"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        var template = internalTemplate("CORN", "FEED_MILL");
        java.util.Map<String, String> fields = new java.util.HashMap<>();
        fields.put("surveyYear", "2026");
        fields.put("surveyMonth", "8");
        fields.put("MKT_REGION", "230200");
        fields.put("MKT_TRADE_DATE", "2026-08-01");
        fields.put("MKT_PURCHASE_BASE_PRICE", "2300");
        fields.put("MKT_SALE_BASE_PRICE", "2380");
        fields.put("MKT_CARRIAGE_BOARD_AMOUNT", "36");
        fields.put("MKT_PACKAGING_AMOUNT", "12");
        fields.put("MKT_FREIGHT_AMOUNT", "72");
        fields.put("MKT_PACKAGING_FORM", "BULK");
        fields.put("MKT_SURVEYOR_NAME", "王雷");
        fields.put("MKT_SURVEYOR_PHONE", "13800000000");
        fields.put("MKT_SAMPLE_NAME", "条件字段失败粮店");
        fields.put("MKT_SAMPLE_CONTACT", "13900000000");
        fields.put("MKT_SAMPLE_LATITUDE", "47.3543");
        fields.put("MKT_SAMPLE_LONGITUDE", "123.9182");
        fields.put("ENDING_INVENTORY", "20");
        List<String> values = template.headers().stream()
                .map(header -> fields.getOrDefault(header, "")).toList();
        byte[] workbook = BusinessImportWorkbook.create(template, List.of(values));

        String response = mvc.perform(multipart("/api/v1/imports/market")
                        .file(new MockMultipartFile("file", "missing-inventory-contract.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", workbook))
                        .param("productCode", "CORN")
                        .param("objectTypeCode", "FEED_MILL")
                        .header("Idempotency-Key", "missing-inventory-contract")
                        .principal(() -> "market-tester"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.statusCode").value("COMPLETED"))
                .andExpect(jsonPath("$.data.importedRows").value(1))
                .andExpect(jsonPath("$.data.failedRows").value(0))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).contains("COMPLETED");
        String recordId = jdbc.sql("SELECT record_id FROM market.market_record").query(String.class).single();
        mvc.perform(post("/api/v1/market-records/{id}/submit", recordId)
                        .principal(() -> "market-tester")
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.inventoryGovernanceStatus").doesNotExist())
                .andExpect(jsonPath("$.data.coreValues.MKT_STORAGE_REGION_CODE").doesNotExist())
                .andExpect(jsonPath("$.data.coreValues.MKT_INVENTORY_HOLDER_CODE").doesNotExist());
        mvc.perform(post("/api/v1/market-records/{id}/approve", recordId)
                        .principal(() -> "production-tester")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));
        assertThat(jdbc.sql("""
                SELECT count(*) FROM market.market_record_core_value
                WHERE record_id=:id AND field_code LIKE 'MKT_INVENTORY_%'
                """).param("id", recordId).query(Long.class).single()).isZero();
    }

    @Test
    void rejectsASoybeanWorkbookFromTheCornMenuBeforeAnyDurableEffect() throws Exception {
        byte[] downloaded = mvc.perform(get("/api/v1/imports/market/template")
                        .param("format", "xlsx")
                        .param("productCode", "SOYBEAN")
                        .param("objectTypeCode", "TRADER")
                        .principal(() -> "market-tester"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        var soybean = internalTemplate("SOYBEAN", "TRADER");
        byte[] workbook = BusinessImportWorkbook.create(soybean, List.of(
                java.util.Collections.nCopies(soybean.headers().size(), "")));

        mvc.perform(multipart("/api/v1/imports/market")
                        .file(new MockMultipartFile("file", "soybean-market.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", workbook))
                        .param("productCode", "CORN")
                        .param("objectTypeCode", "TRADER")
                        .header("Idempotency-Key", "market-context-mismatch")
                        .principal(() -> "market-tester"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("IMPORT_CONTEXT_MISMATCH"));

        assertThat(jdbc.sql("SELECT count(*) FROM market.market_record").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM platform.import_job").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM platform.import_row_result").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM platform.business_audit_event").query(Long.class).single()).isZero();
    }

    @Test
    void workbookFieldsExactlyFollowTheActiveReserveEnterpriseDefinition() throws Exception {
        var response = mvc.perform(get("/api/v1/imports/market/template")
                        .param("format", "xlsx")
                        .param("productCode", "CORN")
                        .param("objectTypeCode", "RESERVE_ENTERPRISE")
                        .principal(() -> "market-tester"))
                .andExpect(status().isOk())
                .andReturn().getResponse();
        assertThat(ContentDisposition.parse(response.getHeader(HttpHeaders.CONTENT_DISPOSITION)).getFilename())
                .isEqualTo("市场-玉米-批量导入模板.xlsx");
        byte[] workbook = response.getContentAsByteArray();

        List<String> labels = withoutTrailingBlanks(
                com.cofco.qiqihar.graintrade.importing.infrastructure.XlsxTable
                        .parseWorksheet(workbook, 1, 256).getFirst());
        assertThat(labels)
                .startsWith("样本点类型", "数据年份", "数据月份", "样本点名称", "地区")
                .contains("采购量（吨）", "销售量（吨）", "现有库存（吨）")
                .endsWith(BusinessImportWorkbook.PHOTO_FILENAMES_LABEL)
                .doesNotContain("库存持有人代码", "货权人代码", "内部治理截止日期");
        assertThat(BusinessImportWorkbook.context(workbook, "MARKET").productCode()).isEqualTo("CORN");
        assertThat(BusinessImportWorkbook.context(workbook, "MARKET").objectTypeCode()).isNull();
    }

    private BusinessImportWorkbook.Template internalTemplate(
            String productCode, String objectTypeCode) {
        return MarketImportTemplate.workbook(market.definition(productCode, objectTypeCode));
    }

    private static java.util.List<String> withoutTrailingBlanks(java.util.List<String> values) {
        int size = values.size();
        while (size > 0 && values.get(size - 1).isBlank()) size--;
        return java.util.List.copyOf(values.subList(0, size));
    }

    @Test
    void rejectsHostileMarketDecimalsBeforeWritingAnyRecord() throws Exception {
        String photoId = uploadEvidence();
        String csv = String.join(",", MarketImportTemplate.HEADERS) + "\n"
                + row(photoId, "1E999999999");

        mvc.perform(multipart("/api/v1/imports/market")
                        .file(new MockMultipartFile("file", "market.csv", "text/csv",
                                csv.getBytes(StandardCharsets.UTF_8)))
                        .param("productCode", "CORN").param("objectTypeCode", "FEED_MILL")
                        .header("Idempotency-Key", "market-hostile-decimal")
                        .principal(() -> "market-tester"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.importedRows").value(0))
                .andExpect(jsonPath("$.data.failedRows").value(1));

        assertThat(jdbc.sql("SELECT count(*) FROM market.market_record").query(Long.class).single()).isZero();
    }

    @Test
    void returnsPerRowErrorsAndRollsBackTheEntireMarketBatch() throws Exception {
        String firstPhoto = uploadEvidence();
        String invalidPhoto = uploadEvidence();
        String csv = String.join(",", MarketImportTemplate.HEADERS) + "\n"
                + row(firstPhoto, "14.6") + row(invalidPhoto, "not-a-decimal");

        String response = mvc.perform(multipart("/api/v1/imports/market")
                        .file(new MockMultipartFile("file", "market.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
                        .param("productCode", "CORN").param("objectTypeCode", "FEED_MILL")
                        .header("Idempotency-Key", "market-import-invalid-row").principal(() -> "market-tester"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.importedRows").value(0))
                .andExpect(jsonPath("$.data.failedRows").value(2))
                .andReturn().getResponse().getContentAsString();
        String jobId = response.replaceFirst("(?s).*?\"id\":\"([^\"]+)\".*", "$1");

        mvc.perform(get("/api/v1/imports/market/{jobId}/errors", jobId)
                        .principal(() -> "market-tester"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("NOT_IMPORTED_ATOMIC_BATCH")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("IMPORT_ROW_VALUE_FORMAT")));
        assertThat(importErrorDownloadAuditCount(jobId)).isEqualTo(1);
        mvc.perform(get("/api/v1/imports/market/{jobId}/errors", jobId)
                        .principal(() -> "production-tester"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IMPORT_ERROR_FILE_NOT_ALLOWED"));
        assertThat(importErrorDownloadAuditCount(jobId)).isEqualTo(1);

        String retryResponse = mvc.perform(post("/api/v1/imports/market/{jobId}/retries", jobId)
                        .principal(() -> "market-tester"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.retryOf").value(jobId))
                .andExpect(jsonPath("$.data.importedRows").value(0))
                .andExpect(jsonPath("$.data.failedRows").value(2))
                .andReturn().getResponse().getContentAsString();
        String retryJobId = retryResponse.replaceFirst("(?s).*?\"id\":\"([^\"]+)\".*", "$1");

        assertThat(jdbc.sql("SELECT count(*) FROM market.market_record").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM platform.import_row_result WHERE import_job_id=:job")
                .param("job", UUID.fromString(jobId)).query(Long.class).single()).isEqualTo(2L);
        assertThat(jdbc.sql("SELECT count(*) FROM platform.import_row_result WHERE import_job_id=:job")
                .param("job", UUID.fromString(retryJobId)).query(Long.class).single()).isEqualTo(2L);
        assertThat(jdbc.sql("SELECT count(*) FROM evidence.evidence_photo WHERE state_code='ATTACHED'")
                .query(Long.class).single()).isZero();
    }

    private long importErrorDownloadAuditCount(String jobId) {
        return jdbc.sql("""
                SELECT count(*) FROM platform.business_audit_event
                WHERE aggregate_type='IMPORT_JOB' AND aggregate_id=:id
                  AND action_code='IMPORT_ERROR_FILE_DOWNLOADED'
                """).param("id", jobId).query(Long.class).single();
    }

    private static String row(String photoId, String moisture) {
        return String.join(",", "CORN", "FEED_MILL", "230200", "2026-08-01", "2300", "2380", "36", "12", "72", "BULK",
                "客户端伪造姓名", "13800000000", "齐齐哈尔第一粮店", "13900000000", "47.3543", "123.9182",
                "12", moisture, photoId) + "\n";
    }

    private String uploadEvidence() throws Exception {
        String response = mvc.perform(multipart("/api/v1/evidence-photos")
                        .file(new MockMultipartFile("file", "market.png", "image/png", png()))
                        .param("capturedAt", "2026-08-09T08:00:00+08:00")
                        .param("latitude", "47.3543").param("longitude", "123.9182")
                        .param("watermarkText", "齐齐哈尔市 市场采集").principal(() -> "market-tester"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return response.replaceFirst("(?s).*?\\\"id\\\":\\\"([^\\\"]+)\\\".*", "$1");
    }

    private static byte[] png() throws Exception {
        BufferedImage image = new BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(new Color(40, 120, 80));
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.dispose();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

}
