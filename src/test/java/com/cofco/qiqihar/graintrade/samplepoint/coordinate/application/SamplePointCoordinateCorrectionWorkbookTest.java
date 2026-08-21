package com.cofco.qiqihar.graintrade.samplepoint.coordinate.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;

class SamplePointCoordinateCorrectionWorkbookTest {
    @Test
    void roundTripsTheVersionBoundFourSheetCorrectionContract() throws Exception {
        UUID batchId = UUID.fromString("95000000-0000-0000-0000-000000000101");
        UUID pointId = UUID.fromString("95000000-0000-0000-0000-000000000102");
        var row = new SamplePointCoordinateCorrectionWorkbook.Row(
                pointId, 7, "测试样本点", "230202", "龙沙区", "SURVEY_SITE",
                new BigDecimal("123.5100"), new BigDecimal("47.9200"), "group-1",
                "binding-1", "修正坐标", new BigDecimal("123.5101"),
                new BigDecimal("47.9201"), "现场重新定位", "已复核门牌位置");

        byte[] bytes = SamplePointCoordinateCorrectionWorkbook.create(batchId, List.of(row));
        var parsed = SamplePointCoordinateCorrectionWorkbook.read(bytes);

        assertThat(sheetNames(bytes)).containsExactly(
                "填写说明", "待修正样本点", "重复坐标组", "导出绑定");
        assertThat(parsed.batchId()).isEqualTo(batchId);
        assertThat(parsed.rows()).containsExactly(row);
    }

    @Test
    void exportsAReadableChineseWorkbookWithPurposeBuiltWidthsAndPrintSettings() throws Exception {
        UUID batchId = UUID.fromString("95000000-0000-0000-0000-000000000111");
        var row = new SamplePointCoordinateCorrectionWorkbook.Row(
                UUID.fromString("95000000-0000-0000-0000-000000000112"), 3,
                "齐齐哈尔市龙沙区测试样本点", "230202", "龙沙区", "SURVEY_SITE",
                new BigDecimal("123.5100"), new BigDecimal("47.9200"),
                "coordinate-group-sha256-value", "row-binding-sha256-value", "", null, null,
                "", "");

        byte[] bytes = SamplePointCoordinateCorrectionWorkbook.create(batchId, List.of(row));
        String styles = entry(bytes, "xl/styles.xml");
        String instructions = entry(bytes, "xl/worksheets/sheet1.xml");
        String corrections = entry(bytes, "xl/worksheets/sheet2.xml");

        assertThat(styles)
                .contains("<name val=\"Arial Unicode MS\"/>")
                .contains("<family val=\"2\"/><charset val=\"134\"/>")
                .contains("<cellStyle name=\"常规\" xfId=\"0\" builtinId=\"0\"/>")
                .contains("wrapText=\"1\"");
        assertThat(instructions)
                .contains("<col min=\"1\" max=\"1\" width=\"18\"")
                .contains("<col min=\"2\" max=\"2\" width=\"78\"")
                .contains("orientation=\"landscape\" fitToWidth=\"1\"");
        assertThat(corrections)
                .contains("showGridLines=\"0\"")
                .contains("<row r=\"1\" ht=\"34\" customHeight=\"1\"")
                .contains("<col min=\"1\" max=\"1\" width=\"38\"")
                .contains("<col min=\"11\" max=\"11\" width=\"16\"")
                .contains("paperSize=\"8\" orientation=\"landscape\" fitToWidth=\"2\"");
    }

    private static List<String> sheetNames(byte[] bytes) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(
                new java.io.ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            for (java.util.zip.ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                if (entry.getName().equals("xl/workbook.xml")) {
                    String xml = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                    java.util.regex.Matcher matcher = java.util.regex.Pattern
                            .compile("<sheet name=\"([^\"]+)\"").matcher(xml);
                    java.util.ArrayList<String> names = new java.util.ArrayList<>();
                    while (matcher.find()) names.add(matcher.group(1));
                    return List.copyOf(names);
                }
            }
        }
        throw new IllegalArgumentException("workbook.xml missing");
    }

    private static String entry(byte[] bytes, String name) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(
                new java.io.ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            for (java.util.zip.ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                if (entry.getName().equals(name)) {
                    return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new IllegalArgumentException(name + " missing");
    }
}
