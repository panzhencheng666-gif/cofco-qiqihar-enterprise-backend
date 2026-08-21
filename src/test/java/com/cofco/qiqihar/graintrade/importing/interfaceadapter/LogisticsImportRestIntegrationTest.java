package com.cofco.qiqihar.graintrade.importing.interfaceadapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.importing.application.LogisticsImportTemplate;
import com.cofco.qiqihar.graintrade.importing.infrastructure.BusinessImportWorkbook;
import com.cofco.qiqihar.graintrade.importing.infrastructure.XlsxTable;
import com.cofco.qiqihar.graintrade.logistics.importing.LogisticsImportDefinition;
import com.cofco.qiqihar.graintrade.logistics.importing.LogisticsImportPort;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import java.util.List;
import javax.sql.DataSource;
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
class LogisticsImportRestIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired DataSource dataSource;
    @Autowired LogisticsImportPort logistics;
    private JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("TRUNCATE platform.import_row_result,platform.import_job,platform.business_audit_event,"
                + "logistics.route_event,logistics.logistics_node RESTART IDENTITY CASCADE").update();
        node("LOG_RAIL", "铁路测试站", "RAIL_NODE");
        node("LOG_ROAD", "公路测试点", "ROAD_NODE");
    }

    @Test
    void publishesAProductSpecificWorkbookAndImportsAllRowsAtomically() throws Exception {
        var response = mvc.perform(get("/api/v1/imports/logistics/template")
                        .queryParam("productCode", "CORN").principal(() -> "logistics-tester"))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(ContentDisposition.parse(response.getHeader(HttpHeaders.CONTENT_DISPOSITION)).getFilename())
                .isEqualTo("物流-玉米-批量导入模板.xlsx");
        byte[] template = response.getContentAsByteArray();
        BusinessImportWorkbook.ImportSheet empty = BusinessImportWorkbook.read(template,
                LogisticsImportTemplate.DOMAIN,
                LogisticsImportTemplate.headers(logisticsDefinition()),
                LogisticsImportTemplate.labels(logisticsDefinition()));
        assertThat(empty.productCode()).isEqualTo("CORN");
        assertThat(empty.objectTypeCode()).isNull();
        assertThat(LogisticsImportTemplate.headers(logisticsDefinition()))
                .containsExactly("数据年份", "数据月份", "填报日期", "物流样本点名称", "地区",
                        "调研人", "调研人联系方式", "物流样本点联系方式", "纬度（度）", "经度（度）", "运输方式",
                        "运输方向", "运输数量（吨）", "物流运价（不含车板价）（元/吨）", "车板价（元/吨）",
                        "填报状态", BusinessImportWorkbook.PHOTO_FILENAMES_LABEL)
                .noneMatch(header -> header.matches(".*[A-Za-z_].*"));
        assertThat(XlsxTable.parseWorksheet(template, 2, 2))
                .anyMatch(row -> row.equals(List.of("填报类别", "物流")))
                .anyMatch(row -> row.equals(List.of("产品品种", "玉米")))
                .noneMatch(row -> row.getFirst().equals("业务类型"))
                .noneMatch(row -> row.getFirst().equals("对象类型"));
        assertThat(LogisticsImportTemplate.workbook("CORN", logisticsDefinition()).rules().stream()
                .filter(rule -> rule.code().equals("运输方式")).findFirst().orElseThrow().options())
                .containsExactly("铁路", "公路");

        List<String> row = LogisticsImportTemplate.codes(logisticsDefinition()).stream()
                .map(this::value).toList();
        byte[] workbook = BusinessImportWorkbook.create(
                LogisticsImportTemplate.workbook(logisticsDefinition()), List.of(row));
        mvc.perform(multipart("/api/v1/imports/logistics")
                        .file(new MockMultipartFile("file", "logistics.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", workbook))
                        .param("productCode", "CORN")
                        .header("Idempotency-Key", "logistics-xlsx-1")
                        .principal(() -> "logistics-tester"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.domainCode").value("LOGISTICS"))
                .andExpect(jsonPath("$.data.importedRows").value(1))
                .andExpect(jsonPath("$.data.failedRows").value(0));

        assertThat(jdbc.sql("SELECT product_code FROM logistics.route_event")
                .query(String.class).single()).isEqualTo("CORN");
        assertThat(jdbc.sql("SELECT created_by FROM logistics.route_event")
                .query(String.class).single()).isEqualTo("logistics-tester");
        assertThat(jdbc.sql("SELECT reporter FROM logistics.route_event")
                .query(String.class).single()).isEqualTo("物流测试员");
    }

    @Test
    void locksAProductNeutralWorkbookToTheSelectedMenuProduct() throws Exception {
        var soybeanDefinition = logistics.definition("SOYBEAN");
        byte[] workbook = BusinessImportWorkbook.create(
                LogisticsImportTemplate.workbook(soybeanDefinition),
                List.of(LogisticsImportTemplate.codes(soybeanDefinition).stream().map(this::value).toList()));

        mvc.perform(multipart("/api/v1/imports/logistics")
                        .file(new MockMultipartFile("file", "soybean-logistics.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", workbook))
                        .param("productCode", "CORN")
                        .header("Idempotency-Key", "logistics-context-mismatch")
                        .principal(() -> "logistics-tester"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.importedRows").value(1))
                .andExpect(jsonPath("$.data.failedRows").value(0));

        assertThat(jdbc.sql("SELECT product_code FROM logistics.route_event").query(String.class).single())
                .isEqualTo("CORN");
        assertThat(jdbc.sql("SELECT count(*) FROM platform.import_job").query(Long.class).single()).isOne();
    }

    @Test
    void rejectsOneInvalidRowWithoutWritingTheOtherwiseValidRow() throws Exception {
        var definition = logisticsDefinition();
        List<String> valid = LogisticsImportTemplate.codes(definition).stream().map(this::value).toList();
        List<String> invalid = LogisticsImportTemplate.codes(definition).stream()
                .map(code -> code.equals("LOG_ROUTE_VOLUME") ? "not-a-number" : value(code)).toList();
        byte[] workbook = BusinessImportWorkbook.create(
                LogisticsImportTemplate.workbook(definition), List.of(valid, invalid));

        String response = mvc.perform(multipart("/api/v1/imports/logistics")
                        .file(new MockMultipartFile("file", "logistics.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", workbook))
                        .param("productCode", "CORN")
                        .header("Idempotency-Key", "logistics-xlsx-invalid")
                        .principal(() -> "logistics-tester"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.importedRows").value(0))
                .andExpect(jsonPath("$.data.failedRows").value(2))
                .andReturn().getResponse().getContentAsString();
        String jobId = response.replaceFirst("(?s).*?\"id\":\"([^\"]+)\".*", "$1");

        mvc.perform(get("/api/v1/imports/logistics/{jobId}/errors", jobId)
                        .principal(() -> "logistics-tester"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("INVALID_LOGISTICS_RECORD")));
        assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.business_audit_event
                WHERE aggregate_type='IMPORT_JOB' AND aggregate_id=:id
                  AND action_code='IMPORT_ERROR_FILE_DOWNLOADED'
                """).param("id", jobId).query(Long.class).single()).isEqualTo(1);
        mvc.perform(get("/api/v1/imports/logistics/{jobId}/errors", jobId)
                .principal(() -> "production-tester"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IMPORT_ERROR_FILE_NOT_ALLOWED"));
        assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.business_audit_event
                WHERE aggregate_type='IMPORT_JOB' AND aggregate_id=:id
                  AND action_code='IMPORT_ERROR_FILE_DOWNLOADED'
                """).param("id", jobId).query(Long.class).single()).isEqualTo(1);

        assertThat(jdbc.sql("SELECT count(*) FROM logistics.route_event")
                .query(Long.class).single()).isZero();
    }

    @Test
    void rejectsAttemptsToOverrideSystemGeneratedWorkbookColumns() throws Exception {
        var definition = logisticsDefinition();
        List<String> row = LogisticsImportTemplate.codes(definition).stream()
                .map(code -> code.equals("fillingDate") ? "2026-08-13" : value(code)).toList();
        byte[] workbook = BusinessImportWorkbook.create(
                LogisticsImportTemplate.workbook(definition), List.of(row));

        String response = mvc.perform(multipart("/api/v1/imports/logistics")
                        .file(new MockMultipartFile("file", "logistics.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", workbook))
                        .param("productCode", "CORN")
                        .header("Idempotency-Key", "logistics-system-column")
                        .principal(() -> "logistics-tester"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.importedRows").value(0))
                .andExpect(jsonPath("$.data.failedRows").value(1))
                .andReturn().getResponse().getContentAsString();
        String jobId = response.replaceFirst("(?s).*?\"id\":\"([^\"]+)\".*", "$1");

        mvc.perform(get("/api/v1/imports/logistics/{jobId}/errors", jobId)
                        .principal(() -> "logistics-tester"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("READONLY_IMPORT_FIELD")));
        assertThat(jdbc.sql("SELECT count(*) FROM logistics.route_event").query(Long.class).single()).isZero();
    }

    private LogisticsImportDefinition logisticsDefinition() {
        return logistics.definition("CORN");
    }

    private String value(String code) {
        return switch (code) {
            case "surveyYear" -> "2026";
            case "surveyMonth" -> "8";
            case "LOG_SAMPLE_NAME" -> "齐齐哈尔物流中心";
            case "LOG_REGION" -> "230200";
            case "LOG_SURVEYOR_NAME" -> "王雷";
            case "LOG_SURVEYOR_PHONE" -> "13800000000";
            case "LOG_SAMPLE_CONTACT" -> "13900000000";
            case "LOG_SAMPLE_LATITUDE" -> "47.354300";
            case "LOG_SAMPLE_LONGITUDE" -> "123.918200";
            case "LOG_TRANSPORT_MODE" -> "RAIL";
            case "LOG_DIRECTION" -> "INFLOW";
            case "LOG_ROUTE_VOLUME" -> "12.5000";
            case "LOG_FREIGHT_RATE" -> "80.2500";
            case "LOG_BOARD_PRICE" -> "2650.0000";
            default -> "";
        };
    }

    private void node(String code, String name, String type) {
        jdbc.sql("""
                INSERT INTO logistics.logistics_node(node_code,node_name,node_type_code,region_code)
                VALUES(:code,:name,:type,'230200')
                """).param("code", code).param("name", name).param("type", type).update();
    }
}
