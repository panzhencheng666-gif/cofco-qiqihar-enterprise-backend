package com.cofco.qiqihar.graintrade.marketintelligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class WorldBankMonthlyAnalysisTest {
    @Test
    void parsesNamedSheetAndRejectsChangedUnits() throws Exception {
        byte[] valid = workbook(false);
        var parsed = WorldBankMonthlyWorkbook.parse(valid);
        assertThat(parsed.observations()).hasSize(13 * WorldBankMonthlySeries.values().length);
        assertThat(parsed.observations().getLast().series()).isEqualTo(WorldBankMonthlySeries.UREA);
        assertThat(parsed.sourceUpdatedOn()).isEqualTo(LocalDate.now().withDayOfMonth(1));
        assertThatThrownBy(() -> WorldBankMonthlyWorkbook.parse(workbook(true)))
                .hasMessageContaining("Source series or unit changed");
    }

    @Test
    void computesOnlyComparableMonthlyChangesAndShowsStaleState() {
        var repository = mock(WorldBankMonthlyRepository.class);
        var current = LocalDate.now().withDayOfMonth(1).minusMonths(1);
        var points = new ArrayList<WorldBankMonthlyRepository.Point>();
        for (int i = 12; i >= 0; i--) {
            points.add(new WorldBankMonthlyRepository.Point(current.minusMonths(i),
                    BigDecimal.valueOf(100 + 10 * (12 - i)), current, "https://source.example/monthly.xlsx"));
        }
        when(repository.points(WorldBankMonthlySeries.MAIZE, 13)).thenReturn(points);
        when(repository.state()).thenReturn(new WorldBankMonthlyRepository.State(
                Instant.now(), Instant.now(), current, null));
        var result = new WorldBankMonthlyAnalysis(repository).snapshot("maize", 12);
        assertThat(result.latest()).isEqualByComparingTo("220");
        assertThat(result.monthChangePct()).isEqualByComparingTo("4.76");
        assertThat(result.yearChangePct()).isEqualByComparingTo("120.00");
        assertThat(result.trailingThreeMonthAverage()).isEqualByComparingTo("210.00");
        assertThat(result.trend()).isEqualTo("RISING");
        assertThat(result.points()).hasSize(12);
        assertThat(result.stale()).isFalse();

        var gap = List.of(points.get(10), points.get(12));
        when(repository.points(WorldBankMonthlySeries.MAIZE, 13)).thenReturn(gap);
        var gapResult = new WorldBankMonthlyAnalysis(repository).snapshot("maize", 12);
        assertThat(gapResult.monthChangePct()).isNull();
        assertThat(gapResult.trend()).isEqualTo("INSUFFICIENT_DATA");
    }

    private byte[] workbook(boolean wrongUnit) throws Exception {
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("Monthly Prices");
            sheet.createRow(0).createCell(0).setCellValue("World Bank Commodity Price Data (The Pink Sheet)");
            sheet.createRow(3).createCell(0).setCellValue("Updated on " + LocalDate.now()
                    .withDayOfMonth(1).format(DateTimeFormatter.ofPattern("MMMM dd, yyyy", Locale.ENGLISH)));
            var heading = sheet.createRow(4);
            var units = sheet.createRow(5);
            var series = WorldBankMonthlySeries.values();
            for (int column = 0; column < series.length; column++) {
                heading.createCell(column + 1).setCellValue(series[column].sourceHeader);
                units.createCell(column + 1).setCellValue(wrongUnit && column == 0 ? "wrong" : series[column].sourceUnit);
            }
            for (int index = 0; index < 13; index++) {
                var row = sheet.createRow(index + 6);
                var month = LocalDate.now().withDayOfMonth(1).minusMonths(12 - index);
                row.createCell(0).setCellValue(month.getYear() + "M" + String.format("%02d", month.getMonthValue()));
                for (int column = 0; column < series.length; column++)
                    row.createCell(column + 1).setCellValue(100 + index + column);
            }
            workbook.write(output);
            return output.toByteArray();
        }
    }
}
