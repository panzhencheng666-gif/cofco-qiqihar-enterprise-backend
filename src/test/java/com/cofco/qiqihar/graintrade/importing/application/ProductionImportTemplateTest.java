package com.cofco.qiqihar.graintrade.importing.application;

import static org.assertj.core.api.Assertions.assertThat;

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
        row.set(template.headers().indexOf("地区"), "230208");
        row.set(template.headers().indexOf("填报人联系方式"), "13800000000");
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
                    assertThat(context.contractVersion()).isNull();
                    assertThat(context.contractDigest()).isNull();
                    assertThat(com.cofco.qiqihar.graintrade.importing.infrastructure.XlsxTable
                            .parseWorksheet(workbook, 2, 2).toString())
                            .doesNotContain("模板版本", "字段契约版本", "字段契约摘要", "sha256:");
                    assertThat(template.headers()).contains("MOISTURE", "PROTEIN", "MILLING_YIELD");
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
                .startsWith("surveyYear", "surveyMonth", "PROD_SAMPLE_NAME", "regionCode", "PROD_CULTIVAR_NAME")
                .contains("PROD_REPORTER_NAME", "PROD_REPORTER_PHONE", "PROD_SAMPLE_CONTACT",
                        "PROD_SAMPLE_LATITUDE", "PROD_SAMPLE_LONGITUDE",
                        "PROD_OPENING_INVENTORY", "PROD_SALES_VOLUME", "PROD_SELF_USE",
                        "PROD_ENDING_INVENTORY")
                .doesNotContain("surveyDate", "PROD_SAMPLE_SUBJECT_CODE", "evidencePhotoId");
        assertThat(template.labels())
                .startsWith("数据年份", "数据月份", "样本点名称", "地区", "具体品种")
                .contains("播种面积（亩）", "预计单产（公斤/亩）")
                .contains("样本点联系方式", "期初库存（吨）", "期末余粮（吨）")
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
