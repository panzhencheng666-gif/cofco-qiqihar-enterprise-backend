package com.cofco.qiqihar.graintrade.marketintelligence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFHyperlinkRun;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
                Instant.parse("2026-09-25T00:00:00Z"), List.of(), List.of(), List.of(), List.of(), List.of());
        try (var document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            var text = document.getParagraphs().stream().map(paragraph -> paragraph.getText())
                    .reduce("", (left, right) -> left + right);
            assertThat(text).contains("全球粮食商情日报", "本区间暂无已采集资讯", "不填充模拟值或预测结论");
        }
    }

    @Test
    void monthlySourcesDisplayStatisticalMonthWithoutTurningItIntoADay() throws Exception {
        var sourceUrl = "https://thedocs.worldbank.org/example.xlsx";
        var bytes = MarketReportController.render(MarketReportController.Period.MONTH,
                new MarketReportController.Range(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1)),
                Instant.parse("2026-09-27T12:00:00Z"), List.of(),
                List.of(new MarketReportController.Observation("grain", LocalDate.of(2026, 9, 24),
                        new BigDecimal("109.74"), "指数点", "https://scs.moa.gov.cn/",
                        Instant.parse("2026-09-27T11:00:00Z"), 1)),
                List.of(new MarketReportController.Observation("maize", LocalDate.of(2026, 8, 1),
                        new BigDecimal("224"), "美元/吨", sourceUrl,
                        Instant.parse("2026-09-27T11:00:00Z"), 1)),
                List.of(),
                List.of(new MarketReportController.Source("world-bank-pink-sheet",
                                Instant.parse("2026-09-27T11:00:00Z"), LocalDate.of(2026, 8, 1), null),
                        new MarketReportController.Source("moa-public-monitor",
                                Instant.parse("2026-09-27T11:00:00Z"), LocalDate.of(2026, 9, 24), null),
                        new MarketReportController.Source("fao-food-price-index",
                                Instant.parse("2026-09-27T11:00:00Z"), LocalDate.of(2026, 8, 1), null)));
        try (var document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            var sources = document.getTables().get(0);
            var domestic = document.getTables().get(1);
            var world = document.getTables().get(2);
            assertThat(sources.getRow(0).getCell(2).getText()).isEqualTo("源最新统计日或月份");
            assertThat(sources.getRow(1).getCell(2).getText()).isEqualTo("2026-08");
            assertThat(sources.getRow(2).getCell(2).getText()).isEqualTo("2026-09-24");
            assertThat(sources.getRow(3).getCell(2).getText()).isEqualTo("2026-08");
            assertThat(domestic.getRow(0).getCell(1).getText()).isEqualTo("统计日");
            assertThat(domestic.getRow(1).getCell(1).getText()).isEqualTo("2026-09-24");
            assertThat(world.getRow(0).getCell(1).getText()).isEqualTo("统计月份");
            assertThat(world.getRow(1).getCell(1).getText()).isEqualTo("2026-08");
            assertThat(world.getRow(1).getCell(2).getText()).isEqualTo("224 美元/吨");
            var source = world.getRow(1).getCell(4);
            assertThat(source.getText()).isEqualTo("查看原文");
            var hyperlink = (XWPFHyperlinkRun) source.getParagraphs().get(0).getRuns().get(0);
            assertThat(hyperlink.getHyperlink(document).getURL()).isEqualTo(sourceUrl);
            assertThat(domestic.getRow(0).getCell(0).getParagraphs().get(0).getRuns().get(0)
                    .getCTR().getRPr().getRFontsArray(0).getEastAsia()).isEqualTo("Microsoft YaHei");
        }
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void downloadedReportIncludesFaoMonthlyIndexWithSource() throws Exception {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec empty = mock(JdbcClient.StatementSpec.class);
        JdbcClient.StatementSpec fao = mock(JdbcClient.StatementSpec.class);
        JdbcClient.MappedQuerySpec emptyRows = mock(JdbcClient.MappedQuerySpec.class);
        JdbcClient.MappedQuerySpec faoRows = mock(JdbcClient.MappedQuerySpec.class);
        when(empty.param(anyString(), any())).thenReturn(empty);
        when(fao.param(anyString(), any())).thenReturn(fao);
        when(empty.query(any(RowMapper.class))).thenReturn(emptyRows);
        when(fao.query(any(RowMapper.class))).thenReturn(faoRows);
        when(emptyRows.list()).thenReturn(List.of());
        when(faoRows.list()).thenReturn(List.of(new MarketReportController.Observation(
                "cereals", LocalDate.of(2026, 8, 1), new java.math.BigDecimal("109.2"),
                "指数点", "https://www.fao.org/", Instant.parse("2026-09-05T00:00:00Z"), 1)));
        when(jdbc.sql(anyString())).thenAnswer(invocation ->
                ((String) invocation.getArgument(0)).contains("fao_food_price_index") ? fao : empty);

        var bytes = new MarketReportController(jdbc).download(
                MarketReportController.Period.MONTH, LocalDate.of(2026, 9, 1)).getBody();
        var statements = ArgumentCaptor.forClass(String.class);
        verify(jdbc, atLeastOnce()).sql(statements.capture());
        assertThat(statements.getAllValues()).anySatisfy(sql ->
                assertThat(sql).contains("'fao-food-price-index'")
                        .doesNotContain("'fao-food-price'"));
        try (var document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            var text = document.getParagraphs().stream().map(paragraph -> paragraph.getText())
                    .reduce("", (left, right) -> left + right);
            var cells = document.getTables().stream().flatMap(table -> table.getRows().stream())
                    .flatMap(row -> row.getTableCells().stream()).map(cell -> cell.getText())
                    .reduce("", (left, right) -> left + right);
            assertThat(text + cells).contains("FAO 谷物价格指数", "109.2 指数点", "查看原文");
            var source = document.getTables().get(3).getRow(1).getCell(4);
            var hyperlink = (XWPFHyperlinkRun) source.getParagraphs().get(0).getRuns().get(0);
            assertThat(hyperlink.getHyperlink(document).getURL()).isEqualTo("https://www.fao.org/");
        }
    }
}
