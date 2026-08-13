package com.cofco.qiqihar.graintrade.importing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cofco.qiqihar.graintrade.importing.application.LogisticsImportTemplate;
import com.cofco.qiqihar.graintrade.importing.application.MarketImportTemplate;
import com.cofco.qiqihar.graintrade.importing.application.ProductionImportTemplate;
import com.cofco.qiqihar.graintrade.importing.infrastructure.BusinessImportWorkbook;
import com.cofco.qiqihar.graintrade.importing.infrastructure.XlsxTable;
import com.cofco.qiqihar.graintrade.logistics.importing.LogisticsImportDefinition;
import com.cofco.qiqihar.graintrade.market.importing.MarketImportDefinition;
import com.cofco.qiqihar.graintrade.production.application.ProductionImportDefinition;
import com.cofco.qiqihar.graintrade.production.application.ProductionSurveyFieldContract;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class BusinessImportWorkbookTest {

    @Test
    void createsOneVersionedProtocolSpecializedForProductionProductAndObjectType() {
        byte[] workbook = BusinessImportWorkbook.create(
                ProductionImportTemplate.workbook("CORN", "FARMER"));

        assertThat(XlsxTable.parseWorksheet(workbook, 1, ProductionImportTemplate.XLSX_HEADERS.size()))
                .containsExactly(ProductionImportTemplate.XLSX_LABELS, ProductionImportTemplate.XLSX_HEADERS);
        assertThat(XlsxTable.parseWorksheet(workbook, 2, 2))
                .contains(
                        java.util.List.of("模板版本", "1"),
                        java.util.List.of("业务类型", "PRODUCTION"),
                        java.util.List.of("产品品种", "CORN"),
                        java.util.List.of("对象类型", "FARMER"),
                        java.util.List.of("填报人", "由登录账号自动记录，不得在模板中填写"),
                        java.util.List.of("处理方式", "5000 条以内即时处理；5001 至 50000 条转入后台任务处理"));
        assertThat(ProductionImportTemplate.XLSX_HEADERS)
                .doesNotContain("productCode", "objectTypeCode", "PROD_REPORTER_NAME");

        var imported = BusinessImportWorkbook.read(workbook, "PRODUCTION",
                ProductionImportTemplate.XLSX_HEADERS, ProductionImportTemplate.XLSX_LABELS);
        assertThat(imported.productCode()).isEqualTo("CORN");
        assertThat(imported.objectTypeCode()).isEqualTo("FARMER");
        assertThat(imported.rows()).isEmpty();
    }

    @Test
    void derivesEveryBusinessDetailAndFactColumnFromTheAuthoritativeDefinition() {
        ProductionImportDefinition definition = new ProductionImportDefinition(
                "CORN", "FARMER", List.of(
                        new ProductionImportDefinition.Group("DETAIL", "调查明细", List.of(
                                new ProductionImportDefinition.Field(
                                        "PROD_FUTURE_DETAIL", "新增调查项", null, 18, 4))),
                        new ProductionImportDefinition.Group("QUALITY", "质量指标", List.of(
                                new ProductionImportDefinition.Field(
                                        "PROD_FUTURE_QUALITY", "新增质量项", "%", 18, 4)))));

        assertThat(ProductionImportTemplate.workbook(definition).headers())
                .containsExactly(
                        "regionCode", "PROD_CULTIVAR_NAME", "surveyDate",
                        "cultivatedAreaMu", "yieldPerMuKilograms", "PROD_REPORTER_PHONE",
                        "PROD_SAMPLE_CONTACT", "PROD_SAMPLE_LATITUDE", "PROD_SAMPLE_LONGITUDE",
                        "PROD_FUTURE_DETAIL", "PROD_FUTURE_QUALITY", "evidencePhotoId");
    }

    @Test
    void carriesTheVersionedProductionFieldRulesIntoGenerationAndStrictReading() {
        var fields = ProductionSurveyFieldContract.fields(List.of());
        ProductionImportDefinition definition = new ProductionImportDefinition(
                "CORN", "FARMER", ProductionSurveyFieldContract.VERSION, fields, List.of());
        BusinessImportWorkbook.Template template = ProductionImportTemplate.workbook(definition);

        assertThat(template.contractVersion()).isEqualTo(ProductionSurveyFieldContract.VERSION);
        assertThat(template.headers()).isEqualTo(fields.stream()
                .filter(com.cofco.qiqihar.graintrade.production.application.ProductionSurveyField::importable)
                .map(com.cofco.qiqihar.graintrade.production.application.ProductionSurveyField::code)
                .toList());
        assertThat(template.headers())
                .contains("PROD_SAMPLE_NAME", "surveyDate", "cultivatedAreaMu")
                .doesNotContain("PROD_SAMPLE_SUBJECT_CODE", "PROD_REPORTER_NAME",
                        "estimatedOutputKilograms", "sample_point_id");

        ArrayList<String> row = new ArrayList<>(Collections.nCopies(template.headers().size(), ""));
        put(row, template.headers(), "regionCode", "230208");
        put(row, template.headers(), "surveyDate", "2026-08-13");
        put(row, template.headers(), "PROD_SAMPLE_NAME", "权威契约测试主体");
        put(row, template.headers(), "PROD_REPORTER_PHONE", "13800000000");
        put(row, template.headers(), "PROD_SAMPLE_CONTACT", "13900000000");
        put(row, template.headers(), "PROD_SAMPLE_LATITUDE", "47.3543");
        put(row, template.headers(), "PROD_SAMPLE_LONGITUDE", "123.9182");
        put(row, template.headers(), "cultivatedAreaMu", "100.25");
        put(row, template.headers(), "yieldPerMuKilograms", "500");
        put(row, template.headers(), "evidencePhotoId", "00000000-0000-0000-0000-000000000011");
        byte[] workbook = BusinessImportWorkbook.create(template, List.of(row));

        assertThat(XlsxTable.parseWorksheet(workbook, 2, 2))
                .contains(List.of("字段契约版本", ProductionSurveyFieldContract.VERSION));
        assertThat(zipEntry(workbook, "xl/worksheets/sheet1.xml"))
                .contains("<dataValidations", "promptTitle=\"必填字段\"", "type=\"decimal\"",
                        "type=\"date\"");
        assertThat(zipEntry(workbook, "xl/styles.xml"))
                .contains("yyyy-mm-dd", "0.##################");
        assertThat(BusinessImportWorkbook.read(workbook, template).rows())
                .containsExactly(row);

        String dateColumn = columnName(template.headers().indexOf("surveyDate") + 1);
        byte[] excelDateWorkbook = replaceZipEntry(workbook, "xl/worksheets/sheet1.xml", xml -> xml.replace(
                "<c r=\"" + dateColumn + "3\" t=\"inlineStr\" s=\"2\"><is><t>2026-08-13</t></is></c>",
                "<c r=\"" + dateColumn + "3\" t=\"n\" s=\"2\"><v>46247</v></c>"));
        assertThat(BusinessImportWorkbook.read(excelDateWorkbook, template).rows().getFirst())
                .element(template.headers().indexOf("surveyDate"))
                .isEqualTo("2026-08-13");

        ArrayList<String> missingRequired = new ArrayList<>(row);
        put(missingRequired, template.headers(), "PROD_REPORTER_PHONE", "");
        assertThatThrownBy(() -> BusinessImportWorkbook.read(
                BusinessImportWorkbook.create(template, List.of(missingRequired)), template))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("XLSX_REQUIRED_VALUE");

        ArrayList<String> badDecimal = new ArrayList<>(row);
        put(badDecimal, template.headers(), "cultivatedAreaMu", "not-a-number");
        assertThatThrownBy(() -> BusinessImportWorkbook.read(
                BusinessImportWorkbook.create(template, List.of(badDecimal)), template))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("XLSX_VALUE_FORMAT");

        BusinessImportWorkbook.Template wrongVersion = new BusinessImportWorkbook.Template(
                template.domainCode(), template.domainLabel(), template.productCode(), template.objectTypeCode(),
                "production-survey-fields-v2", template.headers(), template.labels(), template.rules());
        assertThatThrownBy(() -> BusinessImportWorkbook.read(workbook, wrongVersion))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("XLSX_CONTRACT_MISMATCH");
    }

    @Test
    void derivesMarketColumnsAndExcludesOnlyMenuAccountAndSystemOwnedFields() {
        MarketImportDefinition.Field objectType = marketField(
                "MKT_OBJECT_TYPE", "对象类型", "SELECT");
        MarketImportDefinition.Field reporter = marketField(
                "MKT_REPORTER_NAME", "填报人", "TEXT");
        MarketImportDefinition.Field sample = marketField(
                "MKT_SAMPLE_NAME", "填报对象", "TEXT");
        MarketImportDefinition.Field purchasePrice = marketField(
                "MKT_PURCHASE_BASE_PRICE", "对象采购价格", "DECIMAL");
        MarketImportDefinition.Field salePrice = marketField(
                "MKT_SALE_BASE_PRICE", "对象销售价格", "DECIMAL");
        MarketImportDefinition.Field calculated = marketField(
                "MKT_ACTUAL_TRADE_PRICE", "实际成交价", "READONLY_DECIMAL");
        MarketImportDefinition.Field fact = marketField(
                "ENDING_INVENTORY", "期末库存", "DECIMAL");
        MarketImportDefinition definition = new MarketImportDefinition(
                "CORN", "TRADER",
                List.of(objectType, reporter, sample, purchasePrice, salePrice, calculated),
                List.of(fact));

        assertThat(MarketImportTemplate.workbook(definition).headers())
                .containsExactly("MKT_SAMPLE_NAME", "MKT_PURCHASE_BASE_PRICE",
                        "MKT_SALE_BASE_PRICE", "ENDING_INVENTORY", "evidencePhotoId")
                .doesNotContain("MKT_TRADE_DIRECTION", "MKT_ACTUAL_TRADE_PRICE");
    }

    @Test
    void derivesLogisticsColumnsAndExcludesOnlyAccountAndSystemOwnedFields() {
        LogisticsImportDefinition definition = new LogisticsImportDefinition("CORN", List.of(
                new LogisticsImportDefinition.Field("LOG_PERIOD", "物流监测期", null, true, false),
                new LogisticsImportDefinition.Field("LOG_REPORTER", "物流填报人", null, true, false),
                new LogisticsImportDefinition.Field("LOG_REPORTED_AT", "填报时间", null, false, true),
                new LogisticsImportDefinition.Field("LOG_ROUTE_VOLUME", "运输数量", "吨", true, false)));

        assertThat(LogisticsImportTemplate.workbook(definition).headers())
                .containsExactly("LOG_PERIOD", "LOG_ROUTE_VOLUME");
    }

    private static MarketImportDefinition.Field marketField(
            String code, String label, String controlType) {
        return new MarketImportDefinition.Field(
                code, label, controlType, null, false, 18, 4);
    }

    private static void put(List<String> row, List<String> headers, String code, String value) {
        row.set(headers.indexOf(code), value);
    }

    private static String zipEntry(byte[] workbook, String expectedName) {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(workbook), StandardCharsets.UTF_8)) {
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

    private static byte[] replaceZipEntry(byte[] workbook, String expectedName, UnaryOperator<String> replace) {
        try (ByteArrayInputStream input = new ByteArrayInputStream(workbook);
                ZipInputStream zipInput = new ZipInputStream(input, StandardCharsets.UTF_8);
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                ZipOutputStream zipOutput = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            for (var entry = zipInput.getNextEntry(); entry != null; entry = zipInput.getNextEntry()) {
                zipOutput.putNextEntry(new ZipEntry(entry.getName()));
                byte[] content = zipInput.readAllBytes();
                if (expectedName.equals(entry.getName())) {
                    content = replace.apply(new String(content, StandardCharsets.UTF_8))
                            .getBytes(StandardCharsets.UTF_8);
                }
                zipOutput.write(content);
                zipOutput.closeEntry();
            }
            zipOutput.finish();
            return output.toByteArray();
        } catch (java.io.IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private static String columnName(int oneBased) {
        StringBuilder value = new StringBuilder();
        int column = oneBased;
        while (column > 0) {
            column--;
            value.append((char) ('A' + column % 26));
            column /= 26;
        }
        return value.reverse().toString();
    }

}
