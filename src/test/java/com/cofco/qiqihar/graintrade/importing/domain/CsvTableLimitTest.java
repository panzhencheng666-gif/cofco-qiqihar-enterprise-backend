package com.cofco.qiqihar.graintrade.importing.domain;

import static org.assertj.core.api.Assertions.assertThat;
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
    void rejectsFiveThousandAndOneRowsBeforeAnyProductionInsert() throws Exception {
        StringBuilder csv = new StringBuilder(String.join(",", ProductionImportTemplate.HEADERS)).append('\n');
        csv.append(validRow()).append('\n');
        for (int index = 1; index <= 5_000; index++) {
            csv.append(row(Map.of("surveyDate", "bad-date"))).append('\n');
        }

        assertRejected("row-limit", csv.toString().getBytes(StandardCharsets.UTF_8), "IMPORT_ROW_LIMIT_EXCEEDED");
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
    void rejectsFilesLargerThanTwoMebibytesBeforeParsing() throws Exception {
        byte[] bytes = new byte[2 * 1024 * 1024 + 1];

        assertRejected("byte-limit", bytes, "INVALID_IMPORT_REQUEST");
    }

    private void assertRejected(String key, byte[] bytes, String code) throws Exception {
        mvc.perform(multipart("/api/v1/imports/production")
                        .file(new MockMultipartFile("file", "production.csv", "text/csv", bytes))
                        .header("Idempotency-Key", key).principal(() -> "production-tester"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(code));
        assertThat(count("platform.import_job")).isZero();
        assertThat(count("production.production_record")).isZero();
    }

    private static String validRow() {
        return row(Map.of());
    }

    private static String row(Map<String, String> overrides) {
        Map<String, String> values = new java.util.HashMap<>(Map.ofEntries(
                Map.entry("productCode", "CORN"), Map.entry("objectTypeCode", "FARMER"),
                Map.entry("regionCode", "230200"), Map.entry("cultivarCode", ""),
                Map.entry("surveyDate", "2026-07-31"), Map.entry("cultivatedAreaMu", "10.5"),
                Map.entry("yieldPerMuKilograms", "20"), Map.entry("PROD_REPORTER_NAME", "\u5bfc\u5165\u5458"),
                Map.entry("PROD_REPORTER_PHONE", "13800000000"), Map.entry("PROD_SAMPLE_CONTACT", "13900000000"),
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
