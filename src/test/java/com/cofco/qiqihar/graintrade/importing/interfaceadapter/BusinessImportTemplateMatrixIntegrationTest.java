package com.cofco.qiqihar.graintrade.importing.interfaceadapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.importing.application.LogisticsImportTemplate;
import com.cofco.qiqihar.graintrade.importing.application.MarketImportTemplate;
import com.cofco.qiqihar.graintrade.importing.application.ProductionImportTemplate;
import com.cofco.qiqihar.graintrade.importing.infrastructure.BusinessImportWorkbook;
import com.cofco.qiqihar.graintrade.logistics.importing.LogisticsImportPort;
import com.cofco.qiqihar.graintrade.market.importing.MarketImportPort;
import com.cofco.qiqihar.graintrade.production.application.ProductionImportPort;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.regex.Pattern;
import java.util.zip.ZipInputStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = GrainTradeApplication.class)
@AutoConfigureMockMvc
@UsesProtectedTestDatabase
class BusinessImportTemplateMatrixIntegrationTest {
    private static final String XLSX_MEDIA_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final Pattern CUSTOM_VALIDATION = Pattern.compile(
            "<dataValidation[^>]*type=\\\"custom\\\"[^>]*sqref=\\\"([A-Z]+)2:\\1\\d+\\\"[^>]*>"
                    + ".*?</dataValidation>", Pattern.DOTALL);
    private static final Pattern NEXT_ROW_REQUIRED_FORMULA =
            Pattern.compile("LEN\\(TRIM\\([A-Z]+3\\)\\)");

    @Autowired MockMvc mvc;
    @Autowired DataSource dataSource;
    @Autowired ProductionImportPort production;
    @Autowired MarketImportPort market;
    @Autowired LogisticsImportPort logistics;
    private JdbcClient jdbc;

    @BeforeEach
    void cleanBusinessRows() {
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("TRUNCATE platform.import_row_result,platform.import_job,platform.business_audit_event,"
                + "production.production_record,market.market_record,logistics.route_event,"
                + "logistics.logistics_node,evidence.evidence_photo RESTART IDENTITY CASCADE").update();
        node("MATRIX_RAIL", "矩阵铁路站", "RAIL_NODE");
        node("MATRIX_ROAD", "矩阵公路点", "ROAD_NODE");
    }

    @Test
    void publishesExactlyNineUserFacingProductTemplates() throws Exception {
        List<String> products = jdbc.sql("SELECT code FROM platform.product ORDER BY sort_order")
                .query(String.class).list();
        assertThat(products).containsExactly("CORN", "SOYBEAN", "RICE");
        java.util.Set<String> filenames = new LinkedHashSet<>();

        for (String product : products) {
            for (String domain : List.of("production", "market", "logistics")) {
                var request = get("/api/v1/imports/" + domain + "/template")
                        .param("productCode", product)
                        .principal(() -> domain + "-tester");
                if (!"logistics".equals(domain)) request.param("format", "xlsx");
                var response = mvc.perform(request).andReturn().getResponse();
                assertThat(response.getStatus()).as(domain + " " + product).isEqualTo(200);
                filenames.add(ContentDisposition.parse(
                        response.getHeader(HttpHeaders.CONTENT_DISPOSITION)).getFilename());
                var context = BusinessImportWorkbook.context(
                        response.getContentAsByteArray(), domain.toUpperCase(java.util.Locale.ROOT));
                assertThat(context.productCode()).as(domain + " " + product).isEqualTo(product);
                assertThat(context.objectTypeCode()).as(domain + " " + product).isNull();
                List<String> labels = com.cofco.qiqihar.graintrade.importing.infrastructure.XlsxTable
                        .parseWorksheet(response.getContentAsByteArray(), 1, 256).getFirst();
                labels = labels.stream().takeWhile(label -> !label.isBlank()).toList();
                assertThat(labels).as(domain + " " + product)
                        .endsWith(BusinessImportWorkbook.PHOTO_FILENAMES_LABEL);
                String instructions = com.cofco.qiqihar.graintrade.importing.infrastructure.XlsxTable
                        .parseWorksheet(response.getContentAsByteArray(), 2, 2).toString();
                assertThat(instructions).as(domain + " " + product)
                        .doesNotContain("模板版本", "契约摘要", "sha256:",
                                "PRODUCTION", "MARKET", "LOGISTICS", "CORN", "SOYBEAN", "RICE",
                                "FARMER", "TRADER", "ROUTE_EVENT", "version", "digest", "test");
                if ("production".equals(domain)) assertThat(labels).contains("样本点类型");
                if ("market".equals(domain)) assertThat(labels).contains("对象类型");
            }
        }

        assertThat(filenames).hasSize(9)
                .allMatch(filename -> filename != null && !filename.contains("FARMER")
                        && !filename.contains("TRADER") && !filename.contains("农户")
                        && !filename.contains("贸易商"));
    }

    @Test
    void everyProductionTemplateAcceptsTheFirstTwoDataRowsAndImportsThem() throws Exception {
        List<Context> contexts = contexts("PRODUCTION");
        assertThat(contexts).hasSize(9);

        for (Context context : contexts) {
            String key = context.key();
            var definition = production.importDefinition(context.productCode(), context.objectTypeCode());
            BusinessImportWorkbook.Template template = ProductionImportTemplate.workbook(definition);
            byte[] generated = BusinessImportWorkbook.create(template);
            assertThat(BusinessImportWorkbook.read(generated, template).rows()).as(key).isEmpty();
            assertVersionedOptionalPhotoContract(generated, template, key);
            assertRequiredValidationsUseFirstDataRow(generated, key, true);

            List<String> first = template.headers().stream()
                    .map(header -> productionValue(header, key + "-1")).toList();
            List<String> second = template.headers().stream()
                    .map(header -> productionValue(header, key + "-2")).toList();
            byte[] workbook = BusinessImportWorkbook.create(template, List.of(first, second));
            assertThat(BusinessImportWorkbook.read(workbook, template).rows()).as(key).hasSize(2);
            assertImported(mvc.perform(multipart("/api/v1/imports/production")
                            .file(file(key + ".xlsx", workbook))
                            .param("productCode", context.productCode())
                            .param("objectTypeCode", context.objectTypeCode())
                            .header("Idempotency-Key", "matrix-production-" + key)
                            .principal(() -> "production-tester"))
                    .andReturn().getResponse(), key);
        }
    }

    @Test
    void everyMarketTemplateAcceptsTheFirstTwoDataRowsAndImportsThem() throws Exception {
        List<Context> contexts = contexts("MARKET");
        assertThat(contexts).hasSize(15);

        for (Context context : contexts) {
            String key = context.key();
            var definition = market.definition(context.productCode(), context.objectTypeCode());
            BusinessImportWorkbook.Template template = MarketImportTemplate.workbook(definition);
            byte[] generated = BusinessImportWorkbook.create(template);
            assertThat(BusinessImportWorkbook.read(generated, template).rows()).as(key).isEmpty();
            assertVersionedOptionalPhotoContract(generated, template, key);
            assertRequiredValidationsUseFirstDataRow(generated, key, true);

            List<String> first = template.headers().stream()
                    .map(header -> marketValue(header, key + "-1")).toList();
            List<String> second = template.headers().stream()
                    .map(header -> marketValue(header, key + "-2")).toList();
            byte[] workbook = BusinessImportWorkbook.create(template, List.of(first, second));
            assertThat(MarketImportTemplate.canonicalXlsx(workbook, definition)).as(key).hasSize(3);
            assertImported(mvc.perform(multipart("/api/v1/imports/market")
                            .file(file(key + ".xlsx", workbook))
                            .param("productCode", context.productCode())
                            .param("objectTypeCode", context.objectTypeCode())
                            .header("Idempotency-Key", "matrix-market-" + key)
                            .principal(() -> "market-tester"))
                    .andReturn().getResponse(), key);
        }
    }

    @Test
    void everyLogisticsTemplateAcceptsTheFirstTwoDataRowsAndImportsThem() throws Exception {
        List<String> products = jdbc.sql("SELECT code FROM platform.product ORDER BY sort_order")
                .query(String.class).list();
        assertThat(products).containsExactly("CORN", "SOYBEAN", "RICE");

        for (String product : products) {
            var definition = logistics.definition(product);
            BusinessImportWorkbook.Template template = LogisticsImportTemplate.workbook(product, definition);
            byte[] downloaded = mvc.perform(get("/api/v1/imports/logistics/template")
                            .param("productCode", product)
                            .principal(() -> "logistics-tester"))
                    .andReturn().getResponse().getContentAsByteArray();
            assertThat(BusinessImportWorkbook.read(downloaded, template).rows()).as(product).isEmpty();
            assertVersionedOptionalPhotoContract(downloaded, template, product);
            assertRequiredValidationsUseFirstDataRow(downloaded, product, true);

            List<String> first = LogisticsImportTemplate.codes(definition).stream()
                    .map(code -> logisticsValue(code, product + "-1")).toList();
            List<String> second = LogisticsImportTemplate.codes(definition).stream()
                    .map(code -> logisticsValue(code, product + "-2")).toList();
            byte[] workbook = BusinessImportWorkbook.create(template, List.of(first, second));
            assertThat(BusinessImportWorkbook.read(workbook, template).rows()).as(product).hasSize(2);
            assertImported(mvc.perform(multipart("/api/v1/imports/logistics")
                            .file(file(product + ".xlsx", workbook))
                            .param("productCode", product)
                            .header("Idempotency-Key", "matrix-logistics-" + product)
                            .principal(() -> "logistics-tester"))
                    .andReturn().getResponse(), product);
        }
    }

    private List<Context> contexts(String domain) {
        return jdbc.sql("""
                SELECT applicability.product_code, applicability.object_type_code
                FROM platform.product_object_type applicability
                JOIN platform.product product ON product.code=applicability.product_code
                JOIN platform.object_type object_type ON object_type.code=applicability.object_type_code
                WHERE object_type.business_domain=:domain
                ORDER BY product.sort_order,object_type.sort_order
                """).param("domain", domain).query((row, ignored) ->
                new Context(row.getString(1), row.getString(2))).list();
    }

    private static void assertRequiredValidationsUseFirstDataRow(
            byte[] workbook, String context, boolean expected) {
        String xml = zipEntry(workbook, "xl/worksheets/sheet1.xml");
        var validations = CUSTOM_VALIDATION.matcher(xml);
        int count = 0;
        while (validations.find()) {
            count++;
            String column = validations.group(1);
            assertThat(validations.group()).as(context + " " + column)
                    .contains("LEN(TRIM(" + column + "2))&gt;0");
        }
        assertThat(NEXT_ROW_REQUIRED_FORMULA.matcher(xml).find()).as(context).isFalse();
        if (expected) assertThat(count).as(context).isPositive();
        else assertThat(count).as(context).isZero();
    }

    private static void assertVersionedOptionalPhotoContract(
            byte[] workbook, BusinessImportWorkbook.Template template, String contextLabel) {
        assertThat(template.labels()).as(contextLabel)
                .endsWith(BusinessImportWorkbook.PHOTO_FILENAMES_LABEL);
        assertThat(template.rules().getLast().required()).as(contextLabel).isFalse();
        BusinessImportWorkbook.Context context = BusinessImportWorkbook.context(
                workbook, template.domainCode());
        assertThat(context.contractVersion()).as(contextLabel)
                .isEqualTo(BusinessImportWorkbook.CONTRACT_VERSION);
        assertThat(context.contractDigest()).as(contextLabel)
                .isEqualTo(template.contractDigest())
                .startsWith("sha256:");
    }

    private static void assertImported(
            org.springframework.mock.web.MockHttpServletResponse response, String context) throws Exception {
        assertThat(response.getStatus()).as(context + " response=" + response.getContentAsString()).isEqualTo(201);
        assertThat(response.getContentAsString()).as(context)
                .contains("\"statusCode\":\"COMPLETED\"")
                .contains("\"importedRows\":2")
                .contains("\"failedRows\":0");
    }

    private static MockMultipartFile file(String name, byte[] workbook) {
        return new MockMultipartFile("file", name, XLSX_MEDIA_TYPE, workbook);
    }

    private static String productionValue(String header, String suffix) {
        return switch (header) {
            case "数据年份" -> "2026";
            case "数据月份" -> "8";
            case "样本点名称" -> "产情矩阵-" + suffix;
            case "地区" -> "230208";
            case "具体品种" -> "矩阵品种";
            case "填报人联系方式" -> "13800000000";
            case "样本点联系方式" -> "13900000000";
            case "纬度（度）" -> "47.354300";
            case "经度（度）" -> "123.918200";
            case "播种面积（亩）" -> "100";
            case "预计单产（公斤/亩）" -> "500";
            default -> "";
        };
    }

    private static String marketValue(String code, String suffix) {
        return switch (code) {
            case "surveyYear" -> "2026";
            case "surveyMonth" -> "8";
            case "MKT_SAMPLE_NAME" -> "市场矩阵-" + suffix;
            case "MKT_REGION" -> "230208";
            case "MKT_REPORTER_PHONE" -> "13800000000";
            case "MKT_SAMPLE_CONTACT" -> "13900000000";
            case "MKT_SAMPLE_LATITUDE" -> "47.354300";
            case "MKT_SAMPLE_LONGITUDE" -> "123.918200";
            case "MKT_PURCHASE_BASE_PRICE" -> "2300";
            case "MKT_SALE_BASE_PRICE" -> "2380";
            case "MKT_PACKAGING_FORM" -> "BULK";
            case "TEST_WEIGHT" -> "700";
            case "PURCHASE_VOLUME", "SALES_VOLUME", "MKT_CARRIAGE_BOARD_AMOUNT", "MKT_FREIGHT_AMOUNT",
                    "MOISTURE", "TOXIN", "IMPURITY", "IMPERFECT_GRAIN", "MILDEW", "PROTEIN",
                    "OIL_YIELD", "MILLING_YIELD", "BROWN_RICE_YIELD", "ENDING_INVENTORY" -> "10";
            default -> "";
        };
    }

    private static String logisticsValue(String code, String suffix) {
        return switch (code) {
            case "surveyYear" -> "2026";
            case "surveyMonth" -> "8";
            case "LOG_SAMPLE_NAME" -> "物流矩阵-" + suffix;
            case "LOG_REGION" -> "230208";
            case "LOG_REPORTER_PHONE" -> "13800000000";
            case "LOG_SAMPLE_CONTACT" -> "13900000000";
            case "LOG_SAMPLE_LATITUDE" -> "47.354300";
            case "LOG_SAMPLE_LONGITUDE" -> "123.918200";
            case "LOG_TRANSPORT_MODE" -> "铁路";
            case "LOG_DIRECTION" -> "INFLOW";
            case "LOG_ROUTE_VOLUME" -> "12.5000";
            case "LOG_FREIGHT_RATE" -> "80.2500";
            case "LOG_BOARD_PRICE" -> "2650.0000";
            default -> "";
        };
    }

    private static String zipEntry(byte[] workbook, String expectedName) {
        try (var zip = new ZipInputStream(new ByteArrayInputStream(workbook), StandardCharsets.UTF_8)) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (expectedName.equals(entry.getName())) {
                    return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
            throw new AssertionError("Missing workbook entry " + expectedName);
        } catch (java.io.IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private void node(String code, String name, String type) {
        jdbc.sql("""
                INSERT INTO logistics.logistics_node(node_code,node_name,node_type_code,region_code)
                VALUES(:code,:name,:type,'230208')
                """).param("code", code).param("name", name).param("type", type).update();
    }

    private record Context(String productCode, String objectTypeCode) {
        String key() { return productCode + "-" + objectTypeCode; }
    }
}
