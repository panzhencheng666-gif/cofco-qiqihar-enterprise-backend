package com.cofco.qiqihar.graintrade.regionalproduction.application;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class RegionalPublicIndicatorParserTest {
 @Test void distinguishesAggregateMeatMilkAliasesAndUnitConversions() {
  var values=RegionalPublicIndicatorParser.parse("ANNUAL_QQHR", "2025年齐齐哈尔市国民经济和社会发展统计公报 二、农业 全年猪牛羊禽肉产量28万吨；禽肉产量0.8万吨；生牛奶产量58万吨；粮食总产量20亿斤；蔬菜及食用菌播种面积2万公顷，产量6万吨。三、工业");
  assertThat(values).filteredOn(i->i.label().equals("禽肉产量")).singleElement().satisfies(i->assertThat(i.value()).isEqualByComparingTo("0.8"));
  assertThat(values).filteredOn(i->i.label().contains("牛奶")).hasSize(1);
  assertThat(values).filteredOn(i->i.label().equals("粮食总产量")).singleElement().satisfies(i->assertThat(i.value()).isEqualByComparingTo("100"));
  assertThat(values).filteredOn(i->i.label().equals("蔬菜及食用菌平均单产")).singleElement().satisfies(i->assertThat(i.value()).isEqualByComparingTo("200"));
  assertThat(values).noneMatch(i->i.label().equals("食用菌产量"));
 }
 @Test void keepsPotatoGrainEquivalentDistinctFromFreshWeight() {
  var values=RegionalPublicIndicatorParser.parse("ANNUAL_HLBE", "呼伦贝尔市2025年国民经济和社会发展统计公报 二、农牧业 马铃薯产量（折粮）35.9万吨。三、工业");
  assertThat(values).singleElement().satisfies(i->assertThat(i.label()).isEqualTo("马铃薯折粮产量"));
 }
 @Test void requiresReportYearAndRelevantContent() {
  assertThatThrownBy(()->RegionalPublicIndicatorParser.parse("ANNUAL_QQHR","2026年 网站栏目暂无资料")).isInstanceOf(IllegalArgumentException.class);
  assertThat(RegionalPublicIndicatorParser.parse("GENERIC_PAGE","番茄产量123吨")).isEmpty();
 }
 @Test void retainsAbsentCropsAsMissingAndDoesNotReadIndustrialOutput() {
  var values=RegionalPublicIndicatorParser.parse("ANNUAL_QQHR","2025年齐齐哈尔市国民经济和社会发展统计公报 二、农业 大豆产量10万吨。三、工业 番茄产量20万吨");
  assertThat(values).noneMatch(i->i.label().contains("番茄"));
 }
}
