package com.cofco.qiqihar.graintrade.importing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.importing.application.ProductionImportTemplate;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import java.nio.charset.StandardCharsets;
import java.util.Map;
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
class CsvTableLimitTest {
    @Autowired MockMvc mvc;
    @Autowired DataSource dataSource;
    JdbcClient jdbc;

    @BeforeEach
    void clean() {
        jdbc = JdbcClient.create(dataSource);
        truncateImportEffects();
    }

    @AfterEach
    void cleanAfterEach() {
        truncateImportEffects();
    }

    @Test
    void queuesFiveThousandAndOneRowsWithoutWritingThemInTheRequestThread() throws Exception {
        StringBuilder csv = new StringBuilder(String.join(",", ProductionImportTemplate.HEADERS)).append('\n');
        csv.append(validRow()).append('\n');
        for (int index = 1; index <= 5_000; index++) {
            csv.append(row(Map.of("surveyDate", "bad-date"))).append('\n');
        }

        mvc.perform(multipart("/api/v1/imports/production")
                        .file(new MockMultipartFile("file", "production.csv", "text/csv",
                                csv.toString().getBytes(StandardCharsets.UTF_8)))
                        .param("productCode", "CORN").param("objectTypeCode", "FARMER")
                        .header("Idempotency-Key", "row-limit").principal(() -> "production-tester"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.statusCode").value("QUEUED"));
        assertThat(count("platform.import_job")).isEqualTo(1);
        assertThat(count("production.production_record")).isZero();
    }

    @Test
    void rejectsADataRowWhoseColumnCountDiffersFromTheTemplate() throws Exception {
        String csv = String.join(",", ProductionImportTemplate.HEADERS) + "\n" + validRow() + ",unexpected\n";

        assertRejected("column-limit", csv.getBytes(StandardCharsets.UTF_8), "IMPORT_COLUMN_COUNT_EXCEEDED");
    }

    @Test
    void rejectsACellLongerThanFiveHundredCharacters() throws Exception {
        String csv = String.join(",", ProductionImportTemplate.HEADERS) + "\n"
                + row(Map.of("productCode", "x".repeat(501))) + "\n";

        assertRejected("cell-limit", csv.getBytes(StandardCharsets.UTF_8), "IMPORT_CELL_LIMIT_EXCEEDED");
    }

    @Test
    void rejectsFilesLargerThanTwentyMebibytesBeforeParsing() throws Exception {
        byte[] bytes = new byte[20 * 1024 * 1024 + 1];

        assertRejected("byte-limit", bytes, "INVALID_IMPORT_REQUEST");
    }

    @Test
    void rowLimitWinsBeforeReadingAnOverlongFiveThousandAndFirstRecord() {
        String csv = fullOneColumnTable().append("x".repeat(501)).toString();

        assertLimit(csv, "IMPORT_ROW_LIMIT_EXCEEDED");
    }

    @Test
    void rowLimitWinsBeforeReadingAQuotedFiveThousandAndFirstRecord() {
        String csv = fullOneColumnTable().append(quoted("x".repeat(501))).toString();

        assertLimit(csv, "IMPORT_ROW_LIMIT_EXCEEDED");
    }

    @Test
    void terminalNewlineDoesNotCreateAnExtraLogicalRecordAtTheLimit() {
        assertThat(CsvTable.parse(fullOneColumnTable().toString(), 1)).hasSize(CsvTable.MAX_ROWS + 1);
    }

    @Test
    void anExplicitBlankLineRemainsAnEmptyLogicalRecord() {
        assertThat(CsvTable.parse("header\n\n", 1)).containsExactly(
                java.util.List.of("header"), java.util.List.of(""));
    }

    @Test
    void countsEmojiAsUnicodeCodePoints() {
        String fiveHundred = "\ud83d\ude00".repeat(500);

        assertThat(CsvTable.parse(quoted(fiveHundred), 1).getFirst().getFirst()).isEqualTo(fiveHundred);
        assertLimit(quoted(fiveHundred + "\ud83d\ude00"), "IMPORT_CELL_LIMIT_EXCEEDED");
    }

    @Test
    void preservesBmpCharacterBoundary() {
        String fiveHundred = "\u9f50".repeat(500);

        assertThat(CsvTable.parse(quoted(fiveHundred), 1).getFirst().getFirst()).isEqualTo(fiveHundred);
        assertLimit(quoted(fiveHundred + "\u9f50"), "IMPORT_CELL_LIMIT_EXCEEDED");
    }

    @Test
    void anEscapedQuoteCountsAsOneCellCharacter() {
        String encodedFiveHundred = "x".repeat(499) + "\"\"";

        assertThat(CsvTable.parse(quoted(encodedFiveHundred), 1).getFirst().getFirst())
                .isEqualTo("x".repeat(499) + "\"");
        assertLimit(quoted(encodedFiveHundred + "y"), "IMPORT_CELL_LIMIT_EXCEEDED");
    }

    private void assertRejected(String key, byte[] bytes, String code) throws Exception {
        mvc.perform(multipart("/api/v1/imports/production")
                        .file(new MockMultipartFile("file", "production.csv", "text/csv", bytes))
                        .param("productCode", "CORN").param("objectTypeCode", "FARMER")
                        .header("Idempotency-Key", key).principal(() -> "production-tester"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(code));
        assertThat(count("platform.import_job")).isZero();
        assertThat(count("production.production_record")).isZero();
    }

    private static String validRow() {
        return row(Map.of());
    }

    private static StringBuilder fullOneColumnTable() {
        return new StringBuilder("header\n").append("value\n".repeat(CsvTable.MAX_ROWS));
    }

    private static String quoted(String encodedCell) {
        return "\"" + encodedCell + "\"";
    }

    private static void assertLimit(String csv, String code) {
        assertThatThrownBy(() -> CsvTable.parse(csv, 1))
                .isInstanceOfSatisfying(CsvTable.LimitExceededException.class,
                        exception -> assertThat(exception.code()).isEqualTo(code));
    }

    private static String row(Map<String, String> overrides) {
        Map<String, String> values = new java.util.HashMap<>(Map.ofEntries(
                Map.entry("productCode", "CORN"), Map.entry("objectTypeCode", "FARMER"),
                Map.entry("regionCode", "230200"), Map.entry("cultivarCode", ""),
                Map.entry("surveyDate", "2026-07-31"), Map.entry("cultivatedAreaMu", "10.5"),
                Map.entry("yieldPerMuKilograms", "20"), Map.entry("PROD_REPORTER_NAME", "\u5bfc\u5165\u5458"),
                Map.entry("PROD_SURVEYOR_NAME", "王雷"),
                Map.entry("PROD_SURVEYOR_PHONE", "13800000000"), Map.entry("PROD_SAMPLE_CONTACT", "13900000000"),
                Map.entry("PROD_SAMPLE_LATITUDE", "47.3543"), Map.entry("PROD_SAMPLE_LONGITUDE", "123.9182")));
        values.putAll(overrides);
        return ProductionImportTemplate.HEADERS.stream().map(values::get).collect(java.util.stream.Collectors.joining(","));
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private void truncateImportEffects() {
        JdbcClient.create(dataSource).sql("""
                TRUNCATE platform.import_row_result,platform.import_job,platform.business_audit_event,
                  production.production_record RESTART IDENTITY CASCADE
                """).update();
    }
}
