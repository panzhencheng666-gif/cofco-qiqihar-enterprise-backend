package com.cofco.qiqihar.graintrade.designsample.point.interfaceadapter;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.designsample.point.application.DesignSamplePointImportService;
import com.cofco.qiqihar.graintrade.importing.infrastructure.SamplePointMasterWorkbook;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
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
class DesignSamplePointImportRestIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired DesignSamplePointImportService imports;
    @Autowired DataSource dataSource;
    private JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("TRUNCATE platform.import_row_result,platform.import_job RESTART IDENTITY CASCADE")
                .update();
        jdbc.sql("DELETE FROM platform.design_sample_point").update();
    }

    @Test
    void publishesARealXlsxTemplate() throws Exception {
        mvc.perform(get("/api/v1/design-sample-points/import-template")
                        .principal(() -> "production-tester"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(
                        MediaType.parseMediaType(
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")));
    }

    @Test
    void validatesTheWholeWorkbookThenWritesEveryDesignPointAtomically() throws Exception {
        byte[] workbook = SamplePointMasterWorkbook.create(
                imports.templateDefinition(), List.of(designRow("批量设计点一", "123.95")));

        mvc.perform(multipart("/api/v1/design-sample-points/imports")
                        .file(new MockMultipartFile(
                                "file", "design.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                workbook))
                        .header("Idempotency-Key", "design-import-success")
                        .principal(() -> "production-tester"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.statusCode").value("COMPLETED"))
                .andExpect(jsonPath("$.data.importedRows").value(1))
                .andExpect(jsonPath("$.data.failedRows").value(0));

        org.assertj.core.api.Assertions.assertThat(jdbc.sql(
                        "SELECT count(*) FROM platform.design_sample_point")
                .query(Long.class).single()).isOne();
    }

    @Test
    void oneInvalidRowMakesTheEntireDesignWorkbookZeroWrite() throws Exception {
        Map<String, String> invalid = new java.util.LinkedHashMap<>(
                designRow("批量设计点二", "123.96"));
        invalid.remove("DSP_NAME");
        byte[] workbook = SamplePointMasterWorkbook.create(
                imports.templateDefinition(),
                List.of(designRow("批量设计点一", "123.95"), invalid));

        String response = mvc.perform(multipart("/api/v1/design-sample-points/imports")
                        .file(new MockMultipartFile(
                                "file", "design.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                workbook))
                        .header("Idempotency-Key", "design-import-invalid")
                        .principal(() -> "production-tester"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.statusCode").value("COMPLETED_WITH_ERRORS"))
                .andExpect(jsonPath("$.data.importedRows").value(0))
                .andExpect(jsonPath("$.data.failedRows").value(2))
                .andReturn().getResponse().getContentAsString();

        String id = response.replaceFirst("(?s).*?\\\"id\\\":\\\"([^\\\"]+)\\\".*", "$1");
        mvc.perform(get("/api/v1/design-sample-points/imports/{id}/errors", id)
                        .principal(() -> "production-tester"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("REQUIRED_FIELD_MISSING")));
        org.assertj.core.api.Assertions.assertThat(jdbc.sql(
                        "SELECT count(*) FROM platform.design_sample_point")
                .query(Long.class).single()).isZero();
    }

    private static Map<String, String> designRow(String name, String longitude) {
        return Map.of(
                "domainCode", "PRODUCTION",
                "productCode", "CORN",
                "objectTypeCode", "FARMER",
                "DSP_NAME", name,
                "DSP_REGION_CODE", "230202",
                "DSP_LONGITUDE", longitude,
                "DSP_LATITUDE", "47.35",
                "OBSERVED_ON", "2026-09-02",
                "PROD_AREA_MU", "10");
    }
}
