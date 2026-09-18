package com.cofco.qiqihar.graintrade.reporting.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.cofco.qiqihar.graintrade.reporting.application.ActivityReport;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;

class ActivityReportDocumentTest {
    @Test
    void createsARealDocxWithScopeCutoffAndZeroValueDefinitions() throws Exception {
        ActivityReport report = new ActivityReport(
                "SYSTEM", 7, Instant.parse("2026-09-11T00:00:00Z"),
                Instant.parse("2026-09-18T00:00:00Z"), Instant.parse("2026-09-18T00:00:00Z"),
                null, 12, 9, 2, 0,
                List.of(new ActivityReport.Count("CREATED", "新增", 2),
                        new ActivityReport.Count("DELETED", "删除或退出使用", 0)),
                List.of(new ActivityReport.Count("SAMPLE_NETWORK", "样本网络", 2)),
                List.of(new ActivityReport.Count("TEST", "测试单位", 2)),
                "仅统计当前有效账号。"
        );

        byte[] bytes = new PoiActivityReportDocument().create(report);

        assertThat(bytes).isNotEmpty();
        String documentXml = entry(bytes, "word/document.xml");
        assertThat(documentXml)
                .contains("全系统周期使用总结", "事件截点", "有效用户数", "新增样本点")
                .contains("删除或退出使用", "仅统计当前有效账号", ">0<");
    }

    private static String entry(byte[] bytes, String name) throws Exception {
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (name.equals(entry.getName()))
                    return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("DOCX entry missing: " + name);
    }
}
