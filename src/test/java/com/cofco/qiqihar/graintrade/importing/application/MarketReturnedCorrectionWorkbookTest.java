package com.cofco.qiqihar.graintrade.importing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cofco.qiqihar.graintrade.importing.infrastructure.BusinessImportWorkbook;
import com.cofco.qiqihar.graintrade.importing.infrastructure.XlsxTable;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class MarketReturnedCorrectionWorkbookTest {
    private static final MarketReturnedCorrectionBinding BINDING =
            new MarketReturnedCorrectionBinding("test-only-binding-key");

    @Test
    void createsAndReadsAProductBoundSameRecordCorrectionWorkbook() {
        BusinessImportWorkbook.Template ordinaryTemplate = ordinaryMarketTemplate();
        List<String> firstValues = List.of(
                "贸易商", "2026", "样本点甲", "齐齐哈尔市 / 龙沙区", "47.3543", "123.9182");
        List<String> secondValues = List.of(
                "批发市场", "2026", "样本点乙", "齐齐哈尔市 / 建华区", "47.3620", "123.9550");

        byte[] workbook = MarketReturnedCorrectionWorkbook.create(ordinaryTemplate, List.of(
                new MarketReturnedCorrectionWorkbook.Row("market-001", 4, firstValues),
                new MarketReturnedCorrectionWorkbook.Row("market-002", 9, secondValues)), BINDING);
        BusinessImportWorkbook.Template correctionTemplate =
                MarketReturnedCorrectionWorkbook.template(ordinaryTemplate);

        assertThat(XlsxTable.parseWorksheet(workbook, 1, correctionTemplate.headers().size()))
                .containsExactly(
                        correctionTemplate.labels(),
                        joined("market-001", "4",
                                BINDING.sign("CORN", "market-001", 4), firstValues),
                        joined("market-002", "9",
                                BINDING.sign("CORN", "market-002", 9), secondValues));
        assertThat(correctionTemplate.labels().getFirst()).isEqualTo("原单编号（请勿修改）");
        assertThat(correctionTemplate.headers())
                .doesNotContain(BusinessImportWorkbook.PHOTO_FILENAMES_CODE);
        assertThat(correctionTemplate.labels())
                .doesNotContain(BusinessImportWorkbook.PHOTO_FILENAMES_LABEL,
                        "填报人", "具体品种", "库存权属");

        String workbookXml = zipEntry(workbook, "xl/workbook.xml");
        assertThat(workbookXml)
                .contains("<sheet name=\"退回记录修正\" sheetId=\"1\" r:id=\"rId1\"/>")
                .contains("<sheet name=\"填报说明\" sheetId=\"2\" r:id=\"rId2\"/>");
        assertThat(BusinessImportWorkbook.purpose(workbook))
                .isEqualTo(MarketReturnedCorrectionWorkbook.PURPOSE);
        assertThat(BusinessImportWorkbook.context(workbook, "MARKET").contractDigest())
                .isEqualTo(correctionTemplate.contractDigest());

        String sheetXml = zipEntry(workbook, "xl/worksheets/sheet1.xml");
        assertThat(sheetXml)
                .contains("<col min=\"2\" max=\"2\" width=\"20\" customWidth=\"1\" style=\"3\" hidden=\"1\"/>")
                .contains("<col min=\"3\" max=\"3\" width=\"20\" customWidth=\"1\" style=\"0\" hidden=\"1\"/>")
                .doesNotContain("hidden=\"1\" style=\"0\"");
        String visibleText = XlsxTable.parseWorksheet(workbook, 2, 2).toString();
        assertThat(visibleText)
                .contains("工作簿用途", "退回记录批量修正", "修正后重新提交审核")
                .doesNotContain("开发", "测试", "字段码", "版本号");

        assertThat(MarketReturnedCorrectionWorkbook.read(workbook, ordinaryTemplate, BINDING))
                .containsExactly(
                        new MarketReturnedCorrectionWorkbook.ParsedRow(2, "market-001", 4, firstValues),
                        new MarketReturnedCorrectionWorkbook.ParsedRow(3, "market-002", 9, secondValues));
    }

    @Test
    void keepsOrdinaryImportsAndReturnedCorrectionsMutuallyExclusive() {
        BusinessImportWorkbook.Template ordinaryTemplate = ordinaryMarketTemplate();
        byte[] ordinaryWorkbook = BusinessImportWorkbook.create(ordinaryTemplate);
        List<String> values = List.of(
                "贸易商", "2026", "样本点甲", "齐齐哈尔市 / 龙沙区", "47.3543", "123.9182");
        byte[] correctionWorkbook = MarketReturnedCorrectionWorkbook.create(
                ordinaryTemplate,
                List.of(new MarketReturnedCorrectionWorkbook.Row("market-001", 4, values)), BINDING);

        assertThatThrownBy(() -> MarketReturnedCorrectionWorkbook.read(
                        ordinaryWorkbook, ordinaryTemplate, BINDING))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INVALID_XLSX_PURPOSE");
        assertThatThrownBy(() -> BusinessImportWorkbook.read(correctionWorkbook, ordinaryTemplate))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsChangingAVisibleOriginalIdToAnotherEligibleReturnedRecord() {
        BusinessImportWorkbook.Template ordinaryTemplate = ordinaryMarketTemplate();
        MarketReturnedCorrectionBinding binding =
                new MarketReturnedCorrectionBinding("test-only-binding-key");
        List<String> firstValues = List.of(
                "贸易商", "2026", "样本点甲", "齐齐哈尔市 / 龙沙区", "47.3543", "123.9182");
        List<String> secondValues = List.of(
                "批发市场", "2026", "样本点乙", "齐齐哈尔市 / 建华区", "47.3620", "123.9550");
        byte[] workbook = MarketReturnedCorrectionWorkbook.create(
                ordinaryTemplate,
                List.of(
                        new MarketReturnedCorrectionWorkbook.Row("market-001", 4, firstValues),
                        new MarketReturnedCorrectionWorkbook.Row("market-002", 4, secondValues)),
                binding);

        byte[] tampered = replaceZipEntry(
                workbook,
                "xl/worksheets/sheet1.xml",
                content -> content.replaceFirst("market-001", "market-002"));

        assertThatThrownBy(() ->
                        MarketReturnedCorrectionWorkbook.read(tampered, ordinaryTemplate, binding))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INVALID_RETURNED_CORRECTION_BINDING");
    }

    private static BusinessImportWorkbook.Template ordinaryMarketTemplate() {
        List<String> headers = List.of(
                "objectTypeCode", "surveyYear", "MKT_SAMPLE_NAME", "MKT_REGION",
                "MKT_SAMPLE_LATITUDE", "MKT_SAMPLE_LONGITUDE",
                BusinessImportWorkbook.PHOTO_FILENAMES_CODE);
        List<String> labels = List.of(
                "样本点类型", "数据年份", "样本点名称", "地区", "纬度（度）", "经度（度）",
                BusinessImportWorkbook.PHOTO_FILENAMES_LABEL);
        List<BusinessImportWorkbook.ColumnRule> rules = List.of(
                rule("objectTypeCode", "TEXT", true),
                rule("surveyYear", "TEXT", true),
                rule("MKT_SAMPLE_NAME", "TEXT", true),
                rule("MKT_REGION", "TEXT", true),
                rule("MKT_SAMPLE_LATITUDE", "DECIMAL", true),
                rule("MKT_SAMPLE_LONGITUDE", "DECIMAL", true),
                BusinessImportWorkbook.photoFilenameRule(BusinessImportWorkbook.PHOTO_FILENAMES_CODE));
        return new BusinessImportWorkbook.Template(
                "MARKET", "市场", "CORN", null, null, null, headers, labels, rules);
    }

    private static BusinessImportWorkbook.ColumnRule rule(String code, String type, boolean required) {
        return new BusinessImportWorkbook.ColumnRule(
                code, type, "TEXT", required, List.of(), 18, "DECIMAL".equals(type) ? 4 : 0, null);
    }

    private static List<String> joined(
            String id, String version, String binding, List<String> values) {
        java.util.ArrayList<String> row = new java.util.ArrayList<>();
        row.add(id);
        row.add(version);
        row.add(binding);
        row.addAll(values);
        return List.copyOf(row);
    }

    private static String zipEntry(byte[] workbook, String expectedName) {
        try (ZipInputStream zip = new ZipInputStream(
                new ByteArrayInputStream(workbook), StandardCharsets.UTF_8)) {
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

    private static byte[] replaceZipEntry(
            byte[] workbook, String expectedName, UnaryOperator<String> replace) {
        try (ByteArrayInputStream input = new ByteArrayInputStream(workbook);
                ZipInputStream zipInput = new ZipInputStream(input, StandardCharsets.UTF_8);
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                ZipOutputStream zipOutput = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            for (var entry = zipInput.getNextEntry(); entry != null;
                    entry = zipInput.getNextEntry()) {
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
}
