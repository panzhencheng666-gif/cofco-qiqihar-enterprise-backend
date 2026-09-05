package com.cofco.qiqihar.graintrade.designsample.point.interfaceadapter;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

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
                        .queryParam("domain", "PRODUCTION")
                        .principal(() -> "production-tester"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(
                        MediaType.parseMediaType(
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Content-Disposition", org.hamcrest.Matchers.containsString(
                                "%E8%AE%BE%E8%AE%A1%E6%A0%B7%E6%9C%AC%E7%82%B9")));

        mvc.perform(get("/api/v1/design-sample-points/import-template")
                        .queryParam("domain", "MARKET")
                        .principal(() -> "production-tester"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Content-Disposition", org.hamcrest.Matchers.containsString(
                                "%E8%AE%BE%E8%AE%A1%E6%A0%B7%E6%9C%AC%E7%82%B9")));

        assertThat(imports.templateDefinition().columns())
                .extracting(SamplePointMasterWorkbook.Column::code)
                .containsExactly(
                        "DOMAIN_CODE", "PRODUCT_CODE", "OBJECT_TYPE_CODE",
                        "DSP_NAME", "DSP_REGION_CODE", "DSP_ADDRESS",
                        "DSP_LONGITUDE", "DSP_LATITUDE");
        assertThat(imports.templateDefinition("PRODUCTION").columns().get(0).options())
                .containsExactly("产情", "市场");
        assertThat(imports.templateDefinition("PRODUCTION").columns().get(1).options())
                .contains("玉米", "大豆", "稻谷");
        assertThat(imports.templateDefinition("PRODUCTION").columns().get(2).options())
                .contains("农户", "村委会", "农技站");
        assertThat(imports.templateDefinition("MARKET").columns().get(0).options())
                .containsExactly("产情", "市场");
        assertThat(imports.templateDefinition("MARKET").columns().get(2).options())
                .contains("贸易商", "深加工", "养殖厂", "饲料厂", "农资店");
    }

    @Test
    void workbookChoicesDependOnEachRowsDomainAndProduct() throws Exception {
        byte[] bytes = SamplePointMasterWorkbook.create(imports.templateDefinition());
        assertThat(zipEntry(bytes, "xl/worksheets/sheet1.xml"))
                .contains("INDIRECT($A2&amp;&quot;品种&quot;)")
                .contains("INDIRECT($A2&amp;$B2&amp;&quot;对象类型&quot;)");
        assertThat(zipEntry(bytes, "xl/workbook.xml"))
                .contains("name=\"产情品种\"")
                .contains("name=\"市场品种\"")
                .contains("name=\"市场稻谷对象类型\"");
    }

    private static String zipEntry(byte[] bytes, String name) throws Exception {
        try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(bytes))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.getName().equals(name)) return new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError(name);
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
        assertThat(jdbc.sql("""
                        SELECT values_json->>'DSP_MAINTAINER_NAME',
                               values_json->>'DSP_MAINTAINER_UNIT'
                        FROM platform.design_sample_point
                        """).query((row, index) -> List.of(row.getString(1), row.getString(2))).single())
                .containsExactly("产情测试员", "自动化测试工作单位");
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

    @Test
    void rejectsAProductAndObjectTypeCombinationThatIsNotInTheContract() throws Exception {
        byte[] workbook = SamplePointMasterWorkbook.create(
                imports.templateDefinition("MARKET"), List.of(Map.of(
                        "DOMAIN_CODE", "市场",
                        "PRODUCT_CODE", "稻谷",
                        "OBJECT_TYPE_CODE", "饲料厂",
                        "DSP_NAME", "不合法组合设计点",
                        "DSP_REGION_CODE", "230202",
                        "DSP_ADDRESS", "龙沙区验收详细地址",
                        "DSP_LONGITUDE", "123.95",
                        "DSP_LATITUDE", "47.35")));

        mvc.perform(multipart("/api/v1/design-sample-points/imports")
                        .file(new MockMultipartFile(
                                "file", "design-market.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                workbook))
                        .queryParam("domain", "MARKET")
                        .header("Idempotency-Key", "design-import-invalid-context")
                        .principal(() -> "production-tester"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.statusCode").value("COMPLETED_WITH_ERRORS"))
                .andExpect(jsonPath("$.data.importedRows").value(0))
                .andExpect(jsonPath("$.data.failedRows").value(1));

        assertThat(jdbc.sql("SELECT count(*) FROM platform.design_sample_point")
                .query(Long.class).single()).isZero();
    }


    @Test
    void importsMixedDomainsEvenWithLegacyRequestParameterAndReplaysWithoutDuplicates() throws Exception {
        var market = new java.util.LinkedHashMap<>(designRow("混合市场点", "123.96"));
        market.put("DOMAIN_CODE", "市场");
        market.put("OBJECT_TYPE_CODE", "贸易商");
        byte[] workbook = SamplePointMasterWorkbook.create(imports.templateDefinition(),
                List.of(designRow("混合产情点", "123.95"), market));
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(multipart("/api/v1/design-sample-points/imports")
                            .file(new MockMultipartFile("file", "mixed.xlsx",
                                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", workbook))
                            .queryParam("domain", "PRODUCTION")
                            .header("Idempotency-Key", "mixed-design-import")
                            .principal(() -> "production-tester"))
                    .andExpect(status().is(attempt == 0 ? 201 : 200))
                    .andExpect(jsonPath("$.data.importedRows").value(2))
                    .andExpect(jsonPath("$.data.failedRows").value(0));
        }
        assertThat(jdbc.sql("SELECT domain_code FROM platform.design_sample_point ORDER BY domain_code")
                .query(String.class).list()).containsExactly("MARKET", "PRODUCTION");
    }

    @Test
    void acceptsPriorCategoryWorkbookAndReportsUnknownDomainWithoutPartialWrites() throws Exception {
        var current = imports.templateDefinition();
        var legacyColumns = current.columns().stream().map(column ->
                column.code().equals("DOMAIN_CODE")
                        ? new SamplePointMasterWorkbook.Column(column.code(), column.label(), true, List.of("产情"))
                        : column).toList();
        var legacy = new SamplePointMasterWorkbook.Template(current.kind(), current.version(), current.digest(),
                legacyColumns, "产情类设计参考点");
        var bad = new java.util.LinkedHashMap<>(designRow("错误分类点", "123.97"));
        bad.put("DOMAIN_CODE", "未知分类");
        var bytes = SamplePointMasterWorkbook.create(legacy, List.of(bad, designRow("旧模板有效行", "123.95")));
        String response = mvc.perform(multipart("/api/v1/design-sample-points/imports")
                        .file(new MockMultipartFile("file", "legacy.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes))
                        .header("Idempotency-Key", "legacy-invalid-domain")
                        .principal(() -> "production-tester"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.importedRows").value(0))
                .andExpect(jsonPath("$.data.failedRows").value(2))
                .andReturn().getResponse().getContentAsString();
        String id = response.replaceFirst("(?s).*?\\\"id\\\":\\\"([^\\\"]+)\\\".*", "$1");
        mvc.perform(get("/api/v1/design-sample-points/imports/{id}/errors", id)
                        .principal(() -> "production-tester"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("INVALID_DESIGN_SAMPLE_DOMAIN")));
        assertThat(jdbc.sql("SELECT count(*) FROM platform.design_sample_point").query(Long.class).single()).isZero();
    }

    private static Map<String, String> designRow(String name, String longitude) {
        return Map.of(
                "DOMAIN_CODE", "产情",
                "PRODUCT_CODE", "玉 米",
                "OBJECT_TYPE_CODE", "农户",
                "DSP_NAME", name,
                "DSP_REGION_CODE", "230202",
                "DSP_ADDRESS", "龙沙区验收详细地址",
                "DSP_LONGITUDE", longitude,
                "DSP_LATITUDE", "47.35");
    }
}
