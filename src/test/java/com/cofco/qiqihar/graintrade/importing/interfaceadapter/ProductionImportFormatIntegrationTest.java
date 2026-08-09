package com.cofco.qiqihar.graintrade.importing.interfaceadapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = GrainTradeApplication.class)
@AutoConfigureMockMvc
@UsesProtectedTestDatabase
class ProductionImportFormatIntegrationTest {
    private static final List<String> HEADERS = List.of(
            "productCode", "objectTypeCode", "regionCode", "cultivarCode", "surveyDate",
            "cultivatedAreaMu", "yieldPerMuKilograms", "PROD_REPORTER_NAME", "PROD_REPORTER_PHONE",
            "PROD_SAMPLE_CONTACT", "PROD_SAMPLE_LATITUDE", "PROD_SAMPLE_LONGITUDE", "evidencePhotoId");

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
    }

    @AfterEach void cleanAfter() { clean(); }

    @Test
    void importsXlsxAndReplaysTheSameIdempotentJob() throws Exception {
        String photoA = uploadPhoto();
        String photoB = uploadPhoto();
        byte[] xlsx = xlsx(List.of(HEADERS,
                row("CORN", "2026-08-08", "10", photoA),
                row("CORN", "2026-08-08", "20", photoB)));
        MockMultipartFile file = new MockMultipartFile("file", "production.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsx);

        String first = mvc.perform(multipart("/api/v1/imports/production").file(file)
                        .param("productCode", "CORN").param("objectTypeCode", "FARMER")
                        .header("Idempotency-Key", "xlsx-import-1").principal(() -> "production-tester"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.statusCode").value("COMPLETED"))
                .andExpect(jsonPath("$.data.importedRows").value(2))
                .andReturn().getResponse().getContentAsString();
        String jobId = first.replaceFirst("(?s).*?\"id\":\"([^\"]+)\".*", "$1");

        mvc.perform(multipart("/api/v1/imports/production").file(file)
                        .param("productCode", "CORN").param("objectTypeCode", "FARMER")
                        .header("Idempotency-Key", "xlsx-import-1").principal(() -> "production-tester"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.id").value(jobId))
                .andExpect(jsonPath("$.data.importedRows").value(2));
        assertThat(jdbc.sql("SELECT count(*) FROM production.production_record").query(Long.class).single())
                .isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM evidence.evidence_photo WHERE state_code='ATTACHED'")
                .query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void csvRowErrorMakesTheWholeBatchAtomicAndPreservesRowErrors() throws Exception {
        String photoA = uploadPhoto();
        String photoB = uploadPhoto();
        String csv = String.join(",", HEADERS) + "\n"
                + String.join(",", row("CORN", "2026-08-08", "10", photoA)) + "\n"
                + String.join(",", row("CORN", "bad-date", "20", photoB)) + "\n";

        mvc.perform(multipart("/api/v1/imports/production")
                        .file(new MockMultipartFile("file", "production.csv", "text/csv",
                                csv.getBytes(StandardCharsets.UTF_8)))
                        .param("productCode", "CORN").param("objectTypeCode", "FARMER")
                        .header("Idempotency-Key", "atomic-csv-1").principal(() -> "production-tester"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.statusCode").value("COMPLETED_WITH_ERRORS"))
                .andExpect(jsonPath("$.data.importedRows").value(0))
                .andExpect(jsonPath("$.data.failedRows").value(2));

        assertThat(jdbc.sql("SELECT count(*) FROM production.production_record").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM evidence.evidence_photo WHERE state_code='ATTACHED'")
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT error_code FROM platform.import_row_result ORDER BY row_number")
                .query(String.class).list()).containsExactly("NOT_IMPORTED_ATOMIC_BATCH", "IMPORT_ROW_VALUE_FORMAT");
    }

    private String uploadPhoto() throws Exception {
        String response = mvc.perform(multipart("/api/v1/evidence-photos")
                        .file(new MockMultipartFile("file", "field.png", "image/png", pngBytes()))
                        .param("capturedAt", "2026-08-08T09:00:00+08:00")
                        .param("latitude", "47.3543").param("longitude", "123.9182")
                        .param("watermarkText", "齐齐哈尔 现场采集")
                        .principal(() -> "production-tester"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return response.replaceFirst("(?s).*?\"id\":\"([^\"]+)\".*", "$1");
    }

    private static List<String> row(String product, String date, String area, String photoId) {
        return List.of(product, "FARMER", "230202", "", date, area, "20", "导入填报员",
                "13800000000", "13900000000", "47.3543", "123.9182", photoId);
    }

    private static byte[] xlsx(List<List<String>> rows) throws Exception {
        StringBuilder sheet = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");
        for (int row = 0; row < rows.size(); row++) {
            sheet.append("<row r=\"").append(row + 1).append("\">");
            for (int column = 0; column < rows.get(row).size(); column++) {
                sheet.append("<c r=\"").append(columnName(column)).append(row + 1)
                        .append("\" t=\"inlineStr\"><is><t>")
                        .append(xml(rows.get(row).get(column))).append("</t></is></c>");
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
                      <sheets><sheet name="production" sheetId="1" r:id="rId1"/></sheets>
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

    private static byte[] pngBytes() throws Exception {
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
