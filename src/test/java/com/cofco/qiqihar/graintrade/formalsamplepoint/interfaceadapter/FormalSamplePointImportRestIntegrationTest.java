package com.cofco.qiqihar.graintrade.formalsamplepoint.interfaceadapter;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.formalsamplepoint.application.FormalSamplePointImportService;
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
class FormalSamplePointImportRestIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired FormalSamplePointImportService imports;
    @Autowired DataSource dataSource;
    private JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("TRUNCATE platform.import_row_result,platform.import_job RESTART IDENTITY CASCADE")
                .update();
        jdbc.sql("TRUNCATE registry.sample_point CASCADE").update();
    }

    @Test
    void publishesARealXlsxTemplate() throws Exception {
        mvc.perform(get("/api/v1/formal-sample-points/import-template")
                        .principal(() -> "production-tester"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(
                        MediaType.parseMediaType(
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")));
    }

    @Test
    void importsFormalSamplesOnceAndReplaysWithoutDuplicateWrites() throws Exception {
        byte[] workbook = SamplePointMasterWorkbook.create(
                imports.templateDefinition(), List.of(formalRow("批量正式样本一", "123.94")));
        MockMultipartFile file = new MockMultipartFile(
                "file", "formal.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", workbook);

        mvc.perform(multipart("/api/v1/formal-sample-points/imports")
                        .file(file).header("Idempotency-Key", "formal-import-success")
                        .principal(() -> "production-tester"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.importedRows").value(1));
        mvc.perform(multipart("/api/v1/formal-sample-points/imports")
                        .file(file).header("Idempotency-Key", "formal-import-success")
                        .principal(() -> "production-tester"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replayed").value(true));

        org.assertj.core.api.Assertions.assertThat(jdbc.sql(
                        "SELECT count(*) FROM registry.sample_point")
                .query(Long.class).single()).isOne();
    }

    @Test
    void oneInvalidFormalRowMakesTheEntireWorkbookZeroWrite() throws Exception {
        Map<String, String> invalid = new java.util.LinkedHashMap<>(
                formalRow("批量正式样本二", "123.95"));
        invalid.put("maintainerSubjectId", "missing-employee");
        byte[] workbook = SamplePointMasterWorkbook.create(
                imports.templateDefinition(),
                List.of(formalRow("批量正式样本一", "123.94"), invalid));

        mvc.perform(multipart("/api/v1/formal-sample-points/imports")
                        .file(new MockMultipartFile(
                                "file", "formal.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                workbook))
                        .header("Idempotency-Key", "formal-import-invalid")
                        .principal(() -> "production-tester"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.statusCode").value("COMPLETED_WITH_ERRORS"))
                .andExpect(jsonPath("$.data.importedRows").value(0))
                .andExpect(jsonPath("$.data.failedRows").value(2));

        org.assertj.core.api.Assertions.assertThat(jdbc.sql(
                        "SELECT count(*) FROM registry.sample_point")
                .query(Long.class).single()).isZero();
    }

    private static Map<String, String> formalRow(String name, String longitude) {
        return Map.of(
                "canonicalName", name,
                "regionCode", "230202",
                "address", "龙沙区批量导入地址",
                "longitude", longitude,
                "latitude", "47.31",
                "objectTypeCode", "FARMER",
                "maintainerSubjectId", "production-tester");
    }
}
