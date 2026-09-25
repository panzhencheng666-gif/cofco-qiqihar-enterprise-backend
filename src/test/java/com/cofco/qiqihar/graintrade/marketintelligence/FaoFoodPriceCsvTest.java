package com.cofco.qiqihar.graintrade.marketintelligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class FaoFoodPriceCsvTest {
    @Test
    void acceptsPublishedMonthlySeriesAndRejectsAChangedColumnOrMissingMonth() throws Exception {
        var parsed = FaoFoodPriceCsv.parse(csv(false, false), LocalDate.of(2026, 9, 25));
        assertThat(parsed.latestPeriod()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(parsed.observations()).hasSize(24 * 6);
        assertThat(parsed.observations().getLast().series()).isEqualTo(FaoFoodPriceSeries.SUGAR);
        assertThatThrownBy(() -> FaoFoodPriceCsv.parse(csv(true, false), LocalDate.of(2026, 9, 25)))
                .hasMessageContaining("Changed FAO food-price header");
        assertThatThrownBy(() -> FaoFoodPriceCsv.parse(csv(false, true), LocalDate.of(2026, 9, 25)))
                .hasMessageContaining("Gap or duplicate");
        assertThatThrownBy(() -> FaoFoodPriceCsv.parse(
                new String(csv(false, false), StandardCharsets.UTF_8)
                        .replace("2014-2016=100", "2020-2022=100")
                        .getBytes(StandardCharsets.UTF_8), LocalDate.of(2026, 9, 25)))
                .hasMessageContaining("Unexpected FAO food-price CSV title");
    }

    private byte[] csv(boolean changedHeader, boolean gap) {
        var csv = new StringBuilder("FAO Food Price Index\n2014-2016=100\nDate,")
                .append(changedHeader ? "Food Price" : "Food Price Index")
                .append(",Meat,Dairy,Cereals,Oils,Sugar\n\n");
        var start = LocalDate.of(2024, 9, 1);
        for (int i = 0; i < 24; i++) {
            var month = start.plusMonths(i + (gap && i >= 12 ? 1 : 0));
            csv.append(month.getYear()).append('-')
                    .append(String.format("%02d", month.getMonthValue()))
                    .append(",100,101,102,103,104,105\n");
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }
}
