package com.cofco.qiqihar.graintrade.regionalproduction.application;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class RegionalHistoricalProjectionTest {
 private RegionalAgricultureProfile.Indicator i(int year,String value) {
  return new RegionalAgricultureProfile.Indicator("CROP_VEGETABLE","番茄产量",new BigDecimal(value),"万吨",year,"OBSERVED","原文","公报","https://example.test",null);
 }
 @Test void fitsGrowthFromDataAndPredictsOnlyCurrentMissingYearAndNextYear() {
  var values=RegionalHistoricalProjection.project(List.of(i(2023,"100"),i(2024,"110"),i(2025,"121")),2026);
  assertThat(values).extracting(RegionalAgricultureProfile.Indicator::dataYear).containsExactly(2026,2027);
  assertThat(values.getFirst().value()).isEqualByComparingTo("133.10");
  assertThat(values.getFirst().method()).contains("趋势回归","历史输入","回测未使用未来数据");
 }
 @Test void oneValueUsesPersistenceRatherThanInventingGrowth() {
  var values=RegionalHistoricalProjection.project(List.of(i(2025,"100")),2026);
  assertThat(values).allSatisfy(v->{assertThat(v.value()).isEqualByComparingTo("100");assertThat(v.method()).contains("只有一期");});
 }
 @Test void historyAfterRequestedYearNeverLeaksIntoForecast() {
  var a=RegionalHistoricalProjection.project(List.of(i(2025,"100"),i(2027,"999")),2026);
  assertThat(a).allSatisfy(v->assertThat(v.value()).isEqualByComparingTo("100"));
 }
}
