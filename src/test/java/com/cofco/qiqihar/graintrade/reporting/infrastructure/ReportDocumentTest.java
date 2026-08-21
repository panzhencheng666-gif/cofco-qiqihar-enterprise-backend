package com.cofco.qiqihar.graintrade.reporting.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.cofco.qiqihar.graintrade.reporting.application.ReportPreviewView;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;

class ReportDocumentTest {
    @Test
    void producesByteIdenticalDocumentsWithFixedZipMetadata() throws Exception {
        ReportPreviewView preview = new ReportPreviewView(
                "preview-1", "PRODUCTION_DAILY", "dataset-1", "齐齐哈尔市玉米产情日报",
                "2026年第三季度",
                List.of(new ReportPreviewView.Line("核定数据条数", "3", "正式来源")),
                List.of(new ReportPreviewView.Section("SUMMARY", "综合摘要", "采用三条核定数据。")),
                List.of(),
                Instant.parse("2026-08-12T08:00:00Z"), 0, false);

        byte[] first = ReportDocument.create(preview);
        byte[] second = ReportDocument.create(preview);

        assertThat(second).isEqualTo(first);
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(first))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                assertThat(entry.getTime()).isZero();
            }
        }
    }

    @Test
    void exportsTheSameOrderedMultiProductApprovedSnapshotAcrossAllFormats() throws Exception {
        ReportPreviewView preview = comprehensivePreview();

        Map<String, String> docx = unzip(ReportDocument.create(preview));
        Map<String, String> xlsx = unzip(ReportWorkbook.create(preview));
        byte[] pdfWithProducts = ReportPdf.create(preview);
        byte[] pdfWithoutProducts = ReportPdf.create(new ReportPreviewView(
                preview.id(), preview.definitionCode(), preview.datasetId(), preview.title(),
                preview.dataCutoffLabel(), preview.lines(), preview.sections(), List.of(),
                preview.expiresAt(), preview.version(), preview.legacyReadOnly()));

        String documentXml = docx.get("word/document.xml");
        assertThat(documentXml)
                .contains("审核后数据快照")
                .contains("玉米", "产情监测", "已审核 2 条", "面积：12.5 万亩")
                .contains("大豆", "市场监测", "暂无审核数据")
                .contains("稻谷", "物流监测");
        assertThat(documentXml.indexOf("玉米")).isLessThan(documentXml.indexOf("大豆"));
        assertThat(documentXml.indexOf("大豆")).isLessThan(documentXml.indexOf("稻谷"));

        String worksheetXml = xlsx.get("xl/worksheets/sheet1.xml");
        assertThat(worksheetXml)
                .contains("品种", "业务域", "审核后记录数", "数据截止", "玉米", "产情监测", "面积", "12.5 万亩")
                .contains("大豆", "市场监测", "暂无审核数据")
                .contains("稻谷", "物流监测");
        assertThat(worksheetXml.indexOf("玉米")).isLessThan(worksheetXml.indexOf("大豆"));
        assertThat(worksheetXml.indexOf("大豆")).isLessThan(worksheetXml.indexOf("稻谷"));

        assertThat(pdfWithProducts).startsWith("%PDF-1.4".getBytes(StandardCharsets.US_ASCII));
        assertThat(pdfWithProducts).isNotEqualTo(pdfWithoutProducts);
    }

    private static ReportPreviewView comprehensivePreview() {
        return new ReportPreviewView(
                "preview-comprehensive", "COMPREHENSIVE_DAILY", "dataset-comprehensive",
                "齐齐哈尔市综合经营日报", "2026年08月20日",
                List.of(new ReportPreviewView.Line("审核后数据总量", "2 条", "三品种、四业务域统一快照")),
                List.of(new ReportPreviewView.Section("TODAY_FOCUS", "今日关注", "仅呈现审核通过的数据。")),
                List.of(
                        new ReportPreviewView.Product("CORN", "玉米", List.of(
                                new ReportPreviewView.Domain("PRODUCTION", "产情监测", 2,
                                        "2026-08-20 09:00", List.of(new ReportPreviewView.Line("面积", "12.5 万亩", "审核口径"))),
                                new ReportPreviewView.Domain("MARKET", "市场监测", 0,
                                        null, List.of(new ReportPreviewView.Line("数据状态", "暂无审核数据", "不以零值替代"))))),
                        new ReportPreviewView.Product("SOYBEAN", "大豆", List.of(
                                new ReportPreviewView.Domain("MARKET", "市场监测", 0,
                                        null, List.of(new ReportPreviewView.Line("数据状态", "暂无审核数据", "不以零值替代"))))),
                        new ReportPreviewView.Product("RICE", "稻谷", List.of(
                                new ReportPreviewView.Domain("LOGISTICS", "物流监测", 0,
                                        null, List.of(new ReportPreviewView.Line("数据状态", "暂无审核数据", "不以零值替代")))))),
                Instant.parse("2026-08-20T10:00:00Z"), 0, false);
    }

    private static Map<String, String> unzip(byte[] bytes) throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                entries.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return entries;
    }
}
