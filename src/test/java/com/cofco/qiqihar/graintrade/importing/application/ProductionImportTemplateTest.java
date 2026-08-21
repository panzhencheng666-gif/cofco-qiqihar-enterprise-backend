package com.cofco.qiqihar.graintrade.importing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cofco.qiqihar.graintrade.importing.infrastructure.BusinessImportWorkbook;
import com.cofco.qiqihar.graintrade.production.application.ProductionImportDefinition;
import com.cofco.qiqihar.graintrade.production.application.ProductionFactDefinition;
import com.cofco.qiqihar.graintrade.production.application.ProductionFactGroup;
import com.cofco.qiqihar.graintrade.production.application.ProductionSurveyFieldContract;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProductionImportTemplateTest {

    private static final List<String> MONEY_CODES = List.of(
            "LAND_RENT", "SEED_COST", "PESTICIDE_COST", "FERTILIZER_COST",
            "IRRIGATION_COST", "LABOR_COST", "MACHINERY_COST", "OTHER_COST",
            "INSURANCE_AMOUNT", "SUBSIDY_AMOUNT");

    @Test
    void acceptsTwoDecimalOperationalMoneyAmountsAcrossEveryProductionMoneyField() {
        List<ProductionFactDefinition> moneyFields = MONEY_CODES.stream()
                .map(code -> new ProductionFactDefinition(code, "COST", code,
                        "DECIMAL", "元/亩", null, 18, 0, MONEY_CODES.indexOf(code) + 1))
                .toList();
        ProductionFactGroup cost = new ProductionFactGroup("COST", "成本与补贴", 10, moneyFields);
        ProductionImportDefinition definition = new ProductionImportDefinition(
                "CORN", "FARMER", ProductionSurveyFieldContract.VERSION,
                ProductionSurveyFieldContract.fields(List.of(cost)), List.of());
        BusinessImportWorkbook.Template template = ProductionImportTemplate.workbook(definition);

        for (String code : MONEY_CODES) {
            BusinessImportWorkbook.ColumnRule rule = template.rules().stream()
                    .filter(candidate -> candidate.code().equals(code + "（元/亩）"))
                    .findFirst().orElseThrow();
            assertThat(rule.scale()).as(code).isEqualTo(4);
            assertThatCode(() -> BusinessImportWorkbook.validateCell("733.333", rule))
                    .as(code).doesNotThrowAnyException();
        }
    }

    @Test
    void acceptsHumanQualityPrecisionUpToTheFourDecimalStorageScale() {
        ProductionFactGroup quality = new ProductionFactGroup("QUALITY", "质量指标", 10, List.of(
                new ProductionFactDefinition("TOXIN", "QUALITY", "毒素",
                        "DECIMAL", "%", null, 18, 1, 10),
                new ProductionFactDefinition("IMPURITY", "QUALITY", "杂质",
                        "DECIMAL", "%", null, 18, 1, 20)));
        ProductionImportDefinition definition = new ProductionImportDefinition(
                "CORN", "FARMER", ProductionSurveyFieldContract.VERSION,
                ProductionSurveyFieldContract.fields(List.of(quality)), List.of());
        BusinessImportWorkbook.Template template = ProductionImportTemplate.workbook(definition);
        BusinessImportWorkbook.ColumnRule toxin = template.rules().stream()
                .filter(rule -> rule.code().equals("毒素（%）"))
                .findFirst().orElseThrow();
        BusinessImportWorkbook.ColumnRule impurity = template.rules().stream()
                .filter(rule -> rule.code().equals("杂质（%）"))
                .findFirst().orElseThrow();

        assertThat(toxin.scale()).isEqualTo(4);
        assertThatCode(() -> BusinessImportWorkbook.validateCell("0.001", toxin))
                .doesNotThrowAnyException();
        assertThat(impurity.scale()).isEqualTo(4);
        assertThatCode(() -> BusinessImportWorkbook.validateCell("1.2345", impurity))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> BusinessImportWorkbook.validateCell("1.23456", impurity))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void keepsTechnicalFieldCodesOutOfTheGeneratedWorkbookWhileImportingByTheAuditedMapping() {
        ProductionImportDefinition definition = new ProductionImportDefinition(
                "CORN", "FARMER", ProductionSurveyFieldContract.VERSION,
                ProductionSurveyFieldContract.fields(List.of()), List.of());
        BusinessImportWorkbook.Template template = ProductionImportTemplate.workbook(definition);

        assertThat(template.headers()).containsExactlyElementsOf(template.labels())
                .allMatch(header -> !header.matches(".*[A-Za-z_].*"))
                .contains("数据年份", "数据月份", "播种面积（亩）", "预计单产（公斤/亩）");

        List<String> row = new ArrayList<>(Collections.nCopies(template.headers().size(), ""));
        row.set(template.headers().indexOf("数据年份"), "2026");
        row.set(template.headers().indexOf("数据月份"), "8");
        row.set(template.headers().indexOf("样本点名称"), "克山样本点");
        row.set(template.headers().indexOf("地区"), "230208");
        row.set(template.headers().indexOf("调研人"), "王雷");
        row.set(template.headers().indexOf("调研人联系方式"), "13800000000");
        row.set(template.headers().indexOf("样本点联系方式"), "13900000000");
        row.set(template.headers().indexOf("纬度（度）"), "47.3543");
        row.set(template.headers().indexOf("经度（度）"), "123.9182");
        row.set(template.headers().indexOf("播种面积（亩）"), "100");
        row.set(template.headers().indexOf("预计单产（公斤/亩）"), "500");

        List<List<String>> canonical = ProductionImportTemplate.canonicalXlsx(
                BusinessImportWorkbook.create(template, List.of(row)), definition);

        assertThat(canonical.getFirst()).contains(
                "productCode", "objectTypeCode", "surveyYear", "surveyMonth",
                "regionCode", "cultivatedAreaMu", "yieldPerMuKilograms");
        assertThat(canonical.get(1).get(canonical.getFirst().indexOf("surveyYear"))).isEqualTo("2026");
        assertThat(canonical.get(1).get(canonical.getFirst().indexOf("cultivatedAreaMu"))).isEqualTo("100");
    }

    @Test
    void generatesAndReadsABusinessOnlyWorkbookForEveryEnabledProductionProduct() {
        List.of(
                List.of("CORN", "MOISTURE"),
                List.of("SOYBEAN", "PROTEIN"),
                List.of("RICE", "MILLING_YIELD")).forEach(product -> {
                    BusinessImportWorkbook.Template template = ProductionImportTemplate.workbook(
                            product.get(0), "FARMER");
                    byte[] workbook = BusinessImportWorkbook.create(template);
                    BusinessImportWorkbook.Context context = BusinessImportWorkbook.context(workbook, "PRODUCTION");

                    assertThat(context.productCode()).isEqualTo(product.get(0));
                    assertThat(context.objectTypeCode()).isEqualTo("FARMER");
                    assertThat(context.contractVersion()).isEqualTo(BusinessImportWorkbook.CONTRACT_VERSION);
                    assertThat(context.contractDigest()).startsWith("sha256:");
                    assertThat(com.cofco.qiqihar.graintrade.importing.infrastructure.XlsxTable
                            .parseWorksheet(workbook, 2, 2).toString())
                            .doesNotContain("模板版本", "契约摘要", "sha256:",
                                    BusinessImportWorkbook.CONTRACT_VERSION,
                                    "PRODUCTION", "CORN", "SOYBEAN", "RICE", "FARMER");
                    assertThat(template.headers()).contains("MOISTURE", "PROTEIN", "MILLING_YIELD");
                    assertThat(template.headers()).doesNotContain("PROD_CULTIVAR_NAME", "具体品种");
                    assertThat(product.get(1)).isIn(template.headers());
                });
    }

    @Test
    void exposesOnlyTheAuditedProductionBusinessColumnsWithExplicitDataTime() {
        ProductionFactGroup inventory = new ProductionFactGroup("DETAIL", "调查明细", 10, List.of(
                new ProductionFactDefinition("PROD_OPENING_INVENTORY", "DETAIL", "期初库存",
                        "DECIMAL", "吨", null, 18, 4, 10),
                new ProductionFactDefinition("PROD_SALES_VOLUME", "DETAIL", "销售数量",
                        "DECIMAL", "吨", null, 18, 4, 20),
                new ProductionFactDefinition("PROD_SELF_USE", "DETAIL", "自用数量",
                        "DECIMAL", "吨", null, 18, 4, 30),
                new ProductionFactDefinition("PROD_ENDING_INVENTORY", "DETAIL", "期末余粮",
                        "DECIMAL", "吨", null, 18, 4, 40)));
        ProductionImportDefinition definition = new ProductionImportDefinition(
                "CORN", "FARMER", ProductionSurveyFieldContract.VERSION,
                ProductionSurveyFieldContract.fields(List.of(inventory)), List.of());

        BusinessImportWorkbook.Template template = ProductionImportTemplate.workbook(definition);

        assertThat(ProductionImportTemplate.codes(definition))
                .startsWith("surveyYear", "surveyMonth", "PROD_SAMPLE_NAME", "regionCode")
                .contains("PROD_SURVEYOR_NAME", "PROD_SURVEYOR_PHONE", "PROD_SAMPLE_CONTACT",
                        "PROD_SAMPLE_LATITUDE", "PROD_SAMPLE_LONGITUDE",
                        "PROD_OPENING_INVENTORY", "PROD_SALES_VOLUME", "PROD_SELF_USE",
                        "PROD_ENDING_INVENTORY")
                .doesNotContain("PROD_REPORTER_NAME", "PROD_CULTIVAR_NAME", "surveyDate",
                        "PROD_SAMPLE_SUBJECT_CODE", "evidencePhotoId");
        assertThat(template.labels())
                .startsWith("数据年份", "数据月份", "样本点名称", "地区")
                .contains("播种面积（亩）", "预计单产（公斤/亩）")
                .contains("调研人", "调研人联系方式", "样本点联系方式", "期初库存（吨）", "期末余粮（吨）")
                .doesNotContain("具体品种", "填报人联系方式")
                .doesNotContain("调查日期", "调查对象", "对象类型", "行政区划", "稳定主体码",
                        "填报对象联系方式", "未销售余粮（吨）");
        assertThat(ProductionSurveyFieldContract.fields(List.of(inventory)))
                .anySatisfy(field -> {
                    assertThat(field.code()).isEqualTo("PROD_OPENING_INVENTORY");
                    assertThat(field.displayed()).isTrue();
                    assertThat(field.importable()).isTrue();
                })
                .anySatisfy(field -> {
                    assertThat(field.code()).isEqualTo("PROD_ENDING_INVENTORY");
                    assertThat(field.label()).isEqualTo("期末余粮");
                    assertThat(field.required()).isFalse();
                });
    }

    @Test
    void acceptsThePreviousRegionCodeLabelWithoutChangingItsCanonicalField() {
        ProductionImportDefinition definition = new ProductionImportDefinition(
                "CORN", "FARMER", List.of());
        BusinessImportWorkbook.Template current = ProductionImportTemplate.workbook(definition);
        List<String> legacyLabels = new ArrayList<>(current.labels());
        legacyLabels.set(0, "所在地区代码");
        BusinessImportWorkbook.Template legacy = new BusinessImportWorkbook.Template(
                current.domainCode(), current.domainLabel(), current.productCode(),
                current.objectTypeCode(), current.headers(), legacyLabels);
        List<String> row = new ArrayList<>(Collections.nCopies(legacy.headers().size(), ""));
        row.set(legacy.headers().indexOf("regionCode"), "230200");

        List<List<String>> canonical = ProductionImportTemplate.canonicalXlsx(
                BusinessImportWorkbook.create(legacy, List.of(row)), definition);

        assertThat(canonical.getFirst()).contains("regionCode");
        assertThat(canonical.get(1).get(canonical.getFirst().indexOf("regionCode")))
                .isEqualTo("230200");
    }
}
