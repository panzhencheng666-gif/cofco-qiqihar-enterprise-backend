package com.cofco.qiqihar.graintrade.marketintelligence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

class MarketReportControllerTest {
    @Test
    void calendarRangesUseMondayAndNaturalQuarterBoundaries() {
        var anchor = LocalDate.of(2026, 9, 25);
        assertThat(MarketReportController.range(MarketReportController.Period.DAY, anchor))
                .isEqualTo(new MarketReportController.Range(LocalDate.of(2026, 9, 25), LocalDate.of(2026, 9, 26)));
        assertThat(MarketReportController.range(MarketReportController.Period.WEEK, anchor))
                .isEqualTo(new MarketReportController.Range(LocalDate.of(2026, 9, 21), LocalDate.of(2026, 9, 28)));
        assertThat(MarketReportController.range(MarketReportController.Period.MONTH, anchor))
                .isEqualTo(new MarketReportController.Range(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1)));
        assertThat(MarketReportController.range(MarketReportController.Period.QUARTER, anchor))
                .isEqualTo(new MarketReportController.Range(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 10, 1)));
        assertThat(MarketReportController.range(MarketReportController.Period.YEAR, anchor))
                .isEqualTo(new MarketReportController.Range(LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1)));
    }

    @Test
    void emptyReportStatesEvidenceBoundaryWithoutInventingValues() throws Exception {
        var bytes = MarketReportController.render(MarketReportController.Period.DAY,
                new MarketReportController.Range(LocalDate.of(2026, 9, 25), LocalDate.of(2026, 9, 26)),
                Instant.parse("2026-09-25T00:00:00Z"), List.of(), List.of(), List.of(), List.of());
        try (var document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            var text = document.getParagraphs().stream().map(paragraph -> paragraph.getText())
                    .reduce("", (left, right) -> left + right);
            assertThat(text).contains("全球粮食商情日报", "本区间暂无已采集资讯", "不填充模拟值或预测结论");
        }
    }
}
