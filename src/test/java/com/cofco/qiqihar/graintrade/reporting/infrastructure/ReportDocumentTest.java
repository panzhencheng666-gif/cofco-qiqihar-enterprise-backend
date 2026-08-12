package com.cofco.qiqihar.graintrade.reporting.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.cofco.qiqihar.graintrade.reporting.application.ReportPreviewView;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
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
}
