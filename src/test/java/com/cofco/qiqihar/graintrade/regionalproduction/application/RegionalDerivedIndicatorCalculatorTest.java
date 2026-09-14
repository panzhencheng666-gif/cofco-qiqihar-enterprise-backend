package com.cofco.qiqihar.graintrade.regionalproduction.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class RegionalDerivedIndicatorCalculatorTest {
    @Test
    void derives_reproducible_intensity_metrics_from_current_public_values() {
        var completed = RegionalDerivedIndicatorCalculator.complete(List.of(
                indicator("计划播种面积", "2042.7", "万亩"),
                indicator("种子需求量", "10.5", "万吨"),
                indicator("化肥需求量", "45.1", "万吨")));

        assertThat(completed).filteredOn(value -> value.label().equals("亩均种子需求"))
                .singleElement().satisfies(value -> {
                    assertThat(value.value()).isEqualByComparingTo("5.14");
                    assertThat(value.unit()).isEqualTo("公斤/亩");
                    assertThat(value.dataKind()).isEqualTo("ESTIMATED");
                    assertThat(value.method()).contains(
                            "种子需求量(10.5万吨)×10000000",
                            "计划播种面积(2042.7万亩)×10000",
                            "=5.14公斤/亩");
                });
    }

    private static RegionalAgricultureProfile.Indicator indicator(
            String label, String value, String unit) {
        return new RegionalAgricultureProfile.Indicator("INPUT", label, new BigDecimal(value), unit,
                2026, "OBSERVED", "公开值", "来源", "https://example.test", "2026-09-14T00:30:00Z");
    }
}
