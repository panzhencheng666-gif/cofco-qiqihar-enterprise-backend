package com.cofco.qiqihar.graintrade.regionalproduction.application;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class RegionalPublicIndicatorParserTest {
 @Test void readsHeihePlantingIndustryValueWhenImpactTextSeparatesSubjectFromValue() {
  var values=RegionalPublicIndicatorParser.parse("ANNUAL_HEIHE", "2025年黑河市国民经济和社会发展统计公报 二、农业 其中，种植业受粮食结构调整影响产值实现308.2亿元，增长3.3%；林业产值14.8亿元。三、工业");
  assertThat(values).filteredOn(i -> i.label().equals("种植业产值")).singleElement().satisfies(i -> {
   assertThat(i.value()).isEqualByComparingTo("308.2");
   assertThat(i.kind()).isEqualTo("OBSERVED");
   assertThat(i.method()).contains("种植业受粮食结构调整影响产值实现308.2亿元");
  });
 }
 @Test void doesNotAssignChildCropOutputToEconomicCropAggregate() {
  var values=RegionalPublicIndicatorParser.parse("ANNUAL_HEIHE", "2025年黑河市国民经济和社会发展统计公报。经济作物播种面积40.0万亩，其中，油料播种面积0.54万亩，产量0.05万吨；糖料0.1万亩，产量0.4万吨；蔬菜及食用菌播种面积6.8万亩，产量14.8万吨。");
  assertThat(values).noneMatch(i -> i.label().equals("经济作物产量") || i.label().equals("经济作物平均单产"));
  assertThat(values).filteredOn(i -> i.label().equals("油料产量")).singleElement().satisfies(i -> assertThat(i.value()).isEqualByComparingTo("0.05"));
  assertThat(values).filteredOn(i -> i.label().equals("糖料产量")).singleElement().satisfies(i -> assertThat(i.value()).isEqualByComparingTo("0.4"));
 }
 @Test void rejectsCountyReportEvenWhenParentCityAppearsOnPage() {
  assertThatThrownBy(() -> RegionalPublicIndicatorParser.parse("ANNUAL_QQHR", "齐齐哈尔市政府网站。2025年龙江县国民经济和社会发展统计公报。粮食总产量12万吨。"))
   .isInstanceOf(IllegalArgumentException.class);
 }
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
    @org.junit.jupiter.api.Test void separatesAllGoodsFreightFromGrainFlows() {
        var metrics=RegionalPublicIndicatorParser.parse("ANNUAL_HLBE",
            "2025年呼伦贝尔市国民经济和社会发展统计公报。铁路、公路货运量2.01亿吨。公路货运量0.96亿吨。铁路货运量1.05亿吨，铁路货物周转量400.84亿吨公里。粮食调入量12万吨，粮食调出量20万吨。");
        assertThat(metrics.stream().filter(m -> m.label().equals("铁路货运量")).findFirst().orElseThrow().value()).isEqualByComparingTo("10500");
        assertThat(metrics.stream().filter(m -> m.label().equals("公路货运量")).findFirst().orElseThrow().value()).isEqualByComparingTo("9600");
        assertThat(metrics.stream().filter(m -> m.label().equals("铁路货物周转量")).findFirst().orElseThrow().unit()).isEqualTo("亿吨公里");
        assertThat(metrics.stream().filter(m -> m.label().equals("粮食调出量")).findFirst().orElseThrow().value()).isEqualByComparingTo("20");
    }
}
