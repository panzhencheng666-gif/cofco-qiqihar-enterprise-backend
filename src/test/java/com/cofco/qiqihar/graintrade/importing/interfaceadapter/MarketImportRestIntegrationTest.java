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
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
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
                        .header("Idempotency-Key", "market-import-idempotent").principal(() -> "market-tester"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String second = mvc.perform(multipart("/api/v1/imports/market")
                        .file(new MockMultipartFile("file", "market.csv", "text/csv", csv))
                        .header("Idempotency-Key", "market-import-idempotent").principal(() -> "market-tester"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();

        assertThat(first.replaceFirst("(?s).*?\"id\":\"([^\"]+)\".*", "$1"))
                .isEqualTo(second.replaceFirst("(?s).*?\"id\":\"([^\"]+)\".*", "$1"));
        assertThat(jdbc.sql("SELECT count(*) FROM market.market_record").query(Long.class).single()).isOne();
    }

    @Test
    void importsTheSameServerOwnedTemplateFromXlsx() throws Exception {
        String photoId = uploadEvidence();
        List<String> values = List.of("CORN", "FEED_MILL", "230200", "2026-08-01", "PURCHASE", "2300", "", "36", "12", "72", "BULK",
                "客户端伪造姓名", "13800000000", "齐齐哈尔第一粮店", "13900000000", "47.3543", "123.9182",
                "12", "14.6", photoId);

        mvc.perform(multipart("/api/v1/imports/market")
                        .file(new MockMultipartFile("file", "market.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                xlsx(List.of(MarketImportTemplate.HEADERS, values))))
                        .header("Idempotency-Key", "market-xlsx-1").principal(() -> "market-tester"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.importedRows").value(1));
    }

    @Test
    void rejectsHostileMarketDecimalsBeforeWritingAnyRecord() throws Exception {
        String photoId = uploadEvidence();
        String csv = String.join(",", MarketImportTemplate.HEADERS) + "\n"
                + row(photoId, "1E999999999");

        mvc.perform(multipart("/api/v1/imports/market")
                        .file(new MockMultipartFile("file", "market.csv", "text/csv",
                                csv.getBytes(StandardCharsets.UTF_8)))
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
        return String.join(",", "CORN", "FEED_MILL", "230200", "2026-08-01", "PURCHASE", "2300", "", "36", "12", "72", "BULK",
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

    private static byte[] xlsx(List<List<String>> rows) throws Exception {
        StringBuilder sheet = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");
        for (int row = 0; row < rows.size(); row++) {
            sheet.append("<row r=\"").append(row + 1).append("\">");
            for (int column = 0; column < rows.get(row).size(); column++) {
                sheet.append("<c r=\"").append(columnName(column)).append(row + 1)
                        .append("\" t=\"inlineStr\"><is><t>").append(xml(rows.get(row).get(column)))
                        .append("</t></is></c>");
            }
            sheet.append("</row>");
        }
        sheet.append("</sheetData></worksheet>");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            entry(zip, "[Content_Types].xml", """
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                      <Default Extension="xml" ContentType="application/xml"/>
                      <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                      <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                    </Types>
                    """);
            entry(zip, "xl/workbook.xml", """
                    <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                      xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                      <sheets><sheet name="market" sheetId="1" r:id="rId1"/></sheets>
                    </workbook>
                    """);
            entry(zip, "xl/worksheets/sheet1.xml", sheet.toString());
        }
        return output.toByteArray();
    }

    private static void entry(ZipOutputStream zip, String name, String content) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String columnName(int index) {
        StringBuilder result = new StringBuilder();
        int value = index + 1;
        while (value > 0) {
            value--;
            result.insert(0, (char) ('A' + value % 26));
            value /= 26;
        }
        return result.toString();
    }

    private static String xml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
