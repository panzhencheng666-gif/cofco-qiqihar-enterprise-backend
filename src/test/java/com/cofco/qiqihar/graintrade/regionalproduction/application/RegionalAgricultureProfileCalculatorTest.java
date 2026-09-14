package com.cofco.qiqihar.graintrade.regionalproduction.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class RegionalAgricultureProfileCalculatorTest {
    private final RegionalAgricultureProfileCalculator calculator =
            new RegionalAgricultureProfileCalculator();

    @Test
    void keepsObservedValuesEstimatesMissingCropsAndBuildsThreeYearForecast() {
        var profile = calculator.calculate(
                "230200", "齐齐哈尔市", "PREFECTURE", 2026,
                new BigDecimal("42400000000"),
                List.of(new RegionalAgricultureProfileCalculator.Observation(
                        "CORN", new BigDecimal("17844200"),
                        new BigDecimal("650"), "REGIONAL_OFFICIAL")));

        assertThat(profile.crops()).extracting(RegionalAgricultureProfile.Crop::productCode)
                .containsExactly("CORN", "SOYBEAN", "RICE");
        assertThat(profile.crops().get(0).dataKind()).isEqualTo("OBSERVED");
        assertThat(profile.crops().get(0).plantedAreaMu()).isEqualByComparingTo("17844200");
        assertThat(profile.crops().get(1).dataKind()).isEqualTo("MODEL_ESTIMATE");
        assertThat(profile.crops()).allSatisfy(crop -> {
            assertThat(crop.structurePercent()).isNotNull();
            assertThat(crop.forecasts()).hasSize(3);
            assertThat(crop.forecasts()).extracting(RegionalAgricultureProfile.Forecast::year)
                    .containsExactly(2027, 2028, 2029);
        });
        assertThat(profile.crops().stream()
                .map(RegionalAgricultureProfile.Crop::structurePercent)
                .reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("100.00");
        assertThat(profile.calculationMethod()).contains("结构系数", "复合增长");
        assertThat(profile.automatic()).isTrue();
    }

    @Test
    void producesCalculatedValuesFromBoundaryAreaWhenNoAnnualObservationExists() {
        var profile = calculator.calculate(
                "232700", "大兴安岭地区", "PREFECTURE", 2026,
                new BigDecimal("83000000000"), List.of());

        assertThat(profile.crops()).hasSize(3).allSatisfy(crop -> {
            assertThat(crop.dataKind()).isEqualTo("MODEL_ESTIMATE");
            assertThat(crop.plantedAreaMu()).isPositive();
            assertThat(crop.yieldPerMuKg()).isPositive();
            assertThat(crop.totalOutputKg()).isPositive();
        });
        assertThat(profile.sourceSummary()).contains("公开行政区边界");
    }
}
