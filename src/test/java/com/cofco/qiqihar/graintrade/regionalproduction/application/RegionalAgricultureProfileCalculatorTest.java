package com.cofco.qiqihar.graintrade.regionalproduction.application;

import static org.assertj.core.api.Assertions.assertThat;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RegionalAgricultureProfileCalculatorTest {
    private final RegionalAgricultureProfileCalculator calculator = new RegionalAgricultureProfileCalculator();
    @Test void preservesKnownInputsWithoutInventingOtherCrops() {
        var profile=calculator.calculate("230200","齐齐哈尔市","PREFECTURE",2026,new BigDecimal("42400000000"),
            List.of(new RegionalAgricultureProfileCalculator.Observation("CORN",new BigDecimal("2000"),new BigDecimal("600"),"REGIONAL_OFFICIAL")));
        assertThat(profile.crops()).hasSize(1);
        var crop=profile.crops().getFirst();
        assertThat(crop.totalOutputKg()).isEqualByComparingTo("1200000");
        assertThat(crop.structurePercent()).isEqualByComparingTo("100");
        assertThat(crop.confidencePercent()).isNull();
        assertThat(crop.uncertaintyLowKg()).isNull();
        assertThat(crop.forecasts()).extracting(RegionalAgricultureProfile.Forecast::year).containsExactly(2027);
    }
    @Test void boundaryAloneDoesNotInventCultivationOrYield() {
        assertThat(calculator.calculate("232700","大兴安岭地区","PREFECTURE",2026,
                new BigDecimal("83000000000"),List.of()).crops()).isEmpty();
    }
    @Test void usesFittedRatesAndKeepsUncalibratedWeatherNeutral() {
        var profile=calculator.calculate("230200","齐齐哈尔市","PREFECTURE",2026,new BigDecimal("1000"),
            List.of(new RegionalAgricultureProfileCalculator.Observation("CORN",new BigDecimal("2000"),new BigDecimal("600"),"REGIONAL_OFFICIAL")),
            new RegionalAgricultureProfileCalculator.ForecastContext(new BigDecimal("0.970"),true,
                Map.of("CORN:area",new BigDecimal("0.1"),"CORN:yield",new BigDecimal("0.05"))));
        var prediction=profile.crops().getFirst().forecasts().getFirst();
        assertThat(prediction.totalOutputKg()).isEqualByComparingTo("1386000");
        assertThat(prediction.formula()).contains("1.100000","1.050000","尚无校准因果系数").doesNotContain("0.970");
    }
}
