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
    private JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("""
                TRUNCATE platform.import_row_result,platform.import_job,platform.business_audit_event,
                  market.market_record,evidence.evidence_photo RESTART IDENTITY CASCADE
                """).update();
    }

    @AfterEach
    void clean() {
        jdbc.sql("TRUNCATE platform.import_row_result,platform.import_job,platform.business_audit_event,market.market_record,evidence.evidence_photo RESTART IDENTITY CASCADE").update();
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
        var template = template(downloaded, "CORN", "FEED_MILL");
        java.util.Map<String, String> fields = new java.util.HashMap<>();
        fields.put("MKT_REGION", "230200");
        fields.put("MKT_TRADE_DATE", "2026-08-01");
        fields.put("MKT_PURCHASE_BASE_PRICE", "2300");
        fields.put("MKT_SALE_BASE_PRICE", "2380");
        fields.put("MKT_CARRIAGE_BOARD_AMOUNT", "36");
        fields.put("MKT_PACKAGING_AMOUNT", "12");
        fields.put("MKT_FREIGHT_AMOUNT", "72");
        fields.put("MKT_PACKAGING_FORM", "BULK");
        fields.put("MKT_REPORTER_PHONE", "13800000000");
        fields.put("MKT_SAMPLE_NAME", "齐齐哈尔第一粮店");
        fields.put("MKT_CULTIVAR_NAME", "龙单86");
        fields.put("MKT_SAMPLE_CONTACT", "13900000000");
        fields.put("MKT_SAMPLE_LATITUDE", "47.3543");
        fields.put("MKT_SAMPLE_LONGITUDE", "123.9182");
        fields.put("PURCHASE_VOLUME", "12");
        fields.put("MOISTURE", "14.6");
        fields.put(MarketImportTemplate.EVIDENCE_PHOTO_ID, photoId);
        List<String> values = template.headers().stream()
                .map(header -> fields.getOrDefault(header, "")).toList();
        byte[] workbook = BusinessImportWorkbook.create(
                template, List.of(values));

        mvc.perform(multipart("/api/v1/imports/market")
                        .file(new MockMultipartFile("file", "market.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                workbook))
                        .param("productCode", "CORN").param("objectTypeCode", "FEED_MILL")
                        .header("Idempotency-Key", "market-xlsx-1").principal(() -> "market-tester"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.importedRows").value(1));

        mvc.perform(get("/api/v1/market-records")
                        .queryParam("productCode", "CORN")
                        .queryParam("pageKind", "MONITORING")
                        .queryParam("pageNumber", "0")
                        .queryParam("pageSize", "20")
                        .principal(() -> "market-tester"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].values.MKT_CULTIVAR_NAME").value("龙单86"));
    }

    @Test
    void rejectsASoybeanWorkbookFromTheCornMenuBeforeAnyDurableEffect() throws Exception {
        byte[] downloaded = mvc.perform(get("/api/v1/imports/market/template")
                        .param("format", "xlsx")
                        .param("productCode", "SOYBEAN")
                        .param("objectTypeCode", "TRADER")
                        .principal(() -> "market-tester"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        var soybean = template(downloaded, "SOYBEAN", "TRADER");
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
        byte[] workbook = mvc.perform(get("/api/v1/imports/market/template")
                        .param("format", "xlsx")
                        .param("productCode", "CORN")
                        .param("objectTypeCode", "RESERVE_ENTERPRISE")
                        .principal(() -> "market-tester"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        var template = template(workbook, "CORN", "RESERVE_ENTERPRISE");
        assertThat(com.cofco.qiqihar.graintrade.importing.infrastructure.XlsxTable
                        .parseWorksheet(workbook, 1, template.headers().size()))
                .containsExactly(template.labels(), template.headers());
        assertThat(template.headers())
                .contains("MKT_REGION", "MKT_TRADE_DATE", "MKT_SAMPLE_NAME", "MKT_CULTIVAR_NAME",
                        "MKT_PURCHASE_BASE_PRICE", "MKT_SALE_BASE_PRICE",
                        "OPENING_INVENTORY", "STOCK_OUTFLOW", "ENDING_INVENTORY",
                        MarketImportTemplate.EVIDENCE_PHOTO_ID)
                .doesNotContain("MKT_REPORTER_NAME", "MKT_TRADE_DIRECTION", "MKT_ACTUAL_TRADE_PRICE",
                        "STOCK_INFLOW", "STORAGE_LOSS");
    }

    private static BusinessImportWorkbook.Template template(byte[] workbook,
            String productCode, String objectTypeCode) {
        var rows = com.cofco.qiqihar.graintrade.importing.infrastructure.XlsxTable
                .parseWorksheet(workbook, 1, 256);
        java.util.List<String> labels = withoutTrailingBlanks(rows.get(0));
        java.util.List<String> headers = withoutTrailingBlanks(rows.get(1));
        assertThat(headers).hasSameSizeAs(labels);
        return new BusinessImportWorkbook.Template("MARKET", "市场", productCode, objectTypeCode,
                headers, labels);
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
