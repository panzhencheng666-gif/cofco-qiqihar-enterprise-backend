package com.cofco.qiqihar.graintrade.regionalproduction.application;

import static org.assertj.core.api.Assertions.assertThat;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class RegionalCurrentEstimatesTest {
    @Test void estimatesCurrentYearWithoutUsingItsPublishedAnswer() {
        var series=List.of(i(2022,"100"),i(2023,"110"),i(2024,"120"),i(2025,"130"),i(2026,"900"));
        var row=RegionalCurrentEstimates.compare(series,2026).getFirst();
        assertThat(row.current().model()).isEqualTo("线性趋势");
        assertThat(row.current().value()).isEqualByComparingTo("140");
        assertThat(row.difference()).isEqualByComparingTo("-760");
        assertThat(row.current().inputs()).extracting(RegionalCurrentEstimates.Input::year).doesNotContain(2026);
        assertThat(row.current().selection()).contains("回测","误差");
    }
    @Test void neverLabelsDifferentYearsAsAnErrorComparison() {
        var row=RegionalCurrentEstimates.compare(List.of(i(2023,"100"),i(2024,"110"),i(2025,"120")),2026).getFirst();
        assertThat(row.publicYear()).isEqualTo(2025);
        assertThat(row.difference()).isNull();
        assertThat(row.conclusion()).contains("不同年度");
        assertThat(row.historicalCheck()).isNotNull();
        assertThat(row.historicalCheck().inputs()).extracting(RegionalCurrentEstimates.Input::year).doesNotContain(2025);
    }
    @Test void zeroDenominatorDoesNotInventAnErrorPercentage() {
        var row=RegionalCurrentEstimates.compare(List.of(i(2024,"0"),i(2025,"0"),i(2026,"0")),2026).getFirst();
        assertThat(row.current().value()).isEqualByComparingTo("0");
        assertThat(row.difference()).isEqualByComparingTo("0");
        assertThat(row.differencePercent()).isNull();
    }
    @Test void aPublishedValueWithoutIndependentInputsRemainsUnestimated() {
        var row=RegionalCurrentEstimates.compare(List.of(i(2026,"120")),2026).getFirst();
        assertThat(row.publicValue()).isEqualByComparingTo("120");
        assertThat(row.current()).isNull();
        assertThat(row.conclusion()).contains("独立历史依据不足");
    }
    @Test void respectsUnitsAndDoesNotExtrapolateObsoleteData() {
        var other=new RegionalAgricultureProfile.Indicator("CROP_GRAIN","稻谷产量",new BigDecimal("10"),"吨",2025,"OBSERVED","原文","公报","https://example.org/2025",null);
        assertThat(RegionalCurrentEstimates.compare(List.of(i(2022,"100"),other),2026)).hasSize(2);
        assertThat(RegionalCurrentEstimates.compare(List.of(i(2022,"100")),2026).getFirst().current()).isNull();
    }
    @Test void inputsChangingRecomputesTheEstimateAndKeepsAllSources() {
        var a=RegionalCurrentEstimates.compare(List.of(i(2024,"100"),i(2025,"110")),2026).getFirst();
        var b=RegionalCurrentEstimates.compare(List.of(i(2024,"100"),i(2025,"150")),2026).getFirst();
        assertThat(a.current().value()).isNotEqualByComparingTo(b.current().value());
        assertThat(b.current().inputs()).allSatisfy(input -> assertThat(input.url()).startsWith("https://example.org/"));
    }
    private static RegionalAgricultureProfile.Indicator i(int year,String value) {
        return new RegionalAgricultureProfile.Indicator("CROP_GRAIN","稻谷产量",new BigDecimal(value),"万吨",year,"OBSERVED","原文","公报","https://example.org/"+year,null);
    }
}
