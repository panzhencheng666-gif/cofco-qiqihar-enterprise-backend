package com.cofco.qiqihar.graintrade.importing.application;

import java.util.List;
import com.cofco.qiqihar.graintrade.importing.infrastructure.BusinessImportWorkbook;
import com.cofco.qiqihar.graintrade.production.application.ProductionImportDefinition;

public final class ProductionImportTemplate {
    public static final String DOMAIN = "PRODUCTION";
    public static final List<String> SUBMISSION_METADATA_HEADERS = List.of(
            "PROD_REPORTER_NAME", "PROD_REPORTER_PHONE", "PROD_SAMPLE_CONTACT",
            "PROD_SAMPLE_LATITUDE", "PROD_SAMPLE_LONGITUDE");
    public static final List<String> DETAIL_HEADERS = List.of("PROD_CULTIVAR_NAME", "PROD_SAMPLE_NAME", "PROD_HARVEST_AREA_MU",
            "PROD_AFFECTED_AREA_MU", "PROD_GROWTH_STATUS", "PROD_GROWTH_STAGE", "PROD_OPENING_INVENTORY",
            "PROD_SALES_VOLUME", "PROD_SELF_USE", "PROD_ENDING_INVENTORY", "PROD_INTENDED_AREA_MU",
            "PROD_INTENTION_REASON");
    public static final List<String> QUALITY_HEADERS = List.of("MOISTURE", "TEST_WEIGHT", "TOXIN", "IMPURITY",
            "IMPERFECT_GRAIN", "MILDEW", "PROTEIN", "OIL_YIELD", "MILLING_YIELD", "BROWN_RICE_YIELD");
    public static final List<String> COST_HEADERS = List.of("LAND_RENT", "SEED_COST", "PESTICIDE_COST",
            "FERTILIZER_COST", "IRRIGATION_COST", "LABOR_COST", "MACHINERY_COST", "OTHER_COST");
    public static final List<String> HEADERS = List.of("productCode", "objectTypeCode", "regionCode", "cultivarCode",
            "surveyDate", "cultivatedAreaMu", "yieldPerMuKilograms",
            "PROD_REPORTER_NAME", "PROD_REPORTER_PHONE", "PROD_SAMPLE_CONTACT",
            "PROD_SAMPLE_LATITUDE", "PROD_SAMPLE_LONGITUDE", "evidencePhotoId");
    public static final List<String> XLSX_HEADERS = List.of("regionCode", "PROD_CULTIVAR_NAME", "surveyDate",
            "cultivatedAreaMu", "yieldPerMuKilograms", "PROD_REPORTER_PHONE", "PROD_SAMPLE_CONTACT",
            "PROD_SAMPLE_LATITUDE", "PROD_SAMPLE_LONGITUDE", "PROD_SAMPLE_NAME", "PROD_HARVEST_AREA_MU",
            "PROD_AFFECTED_AREA_MU", "PROD_GROWTH_STATUS", "PROD_GROWTH_STAGE", "PROD_OPENING_INVENTORY",
            "PROD_SALES_VOLUME", "PROD_SELF_USE", "PROD_ENDING_INVENTORY", "PROD_INTENDED_AREA_MU",
            "PROD_INTENTION_REASON", "MOISTURE", "TEST_WEIGHT", "TOXIN", "IMPURITY", "IMPERFECT_GRAIN",
            "MILDEW", "PROTEIN", "OIL_YIELD", "MILLING_YIELD", "BROWN_RICE_YIELD", "LAND_RENT", "SEED_COST",
            "PESTICIDE_COST", "FERTILIZER_COST", "IRRIGATION_COST", "LABOR_COST", "MACHINERY_COST", "OTHER_COST",
            "INSURANCE_AMOUNT", "SUBSIDY_AMOUNT", "evidencePhotoId");
    public static final List<String> XLSX_CORE_HEADERS = XLSX_HEADERS.subList(0, 9);
    public static final List<String> XLSX_CANONICAL_HEADERS = java.util.stream.Stream.of(
            List.of("productCode", "objectTypeCode", "PROD_REPORTER_NAME"), XLSX_HEADERS)
            .flatMap(List::stream).toList();
    public static final List<String> XLSX_LABELS = List.of("所在地区", "具体品种", "调查日期",
            "种植面积（亩）", "权威采用单产（公斤/亩）", "填报人联系方式", "填报对象联系方式",
            "纬度（度）", "经度（度）", "填报对象", "预计收获面积（亩）", "灾损面积（亩）",
            "当前长势", "生育阶段", "期初库存（吨）", "销售数量（吨）", "自用数量（吨）", "期末余粮（吨）",
            "下年度意向面积（亩）", "调整原因", "水分（%）", "容重（克/升）", "毒素（%）", "杂质（%）",
            "不完善粒（%）", "霉变（%）", "蛋白（%）", "出油率（%）", "出米率（%）", "糙米率（%）",
            "地租（元/亩）", "种子费用（元/亩）", "农药费用（元/亩）", "化肥费用（元/亩）", "灌溉费用（元/亩）",
            "人工费用（元/亩）", "机耕费用（元/亩）", "其他成本（元/亩）", "保险金额（元）", "补贴金额（元）", "现场水印照片编号");
    public static final List<String> XLSX_CORE_LABELS = XLSX_LABELS.subList(0, 9);
    private ProductionImportTemplate() {}
    public static String csv() { return String.join(",", HEADERS) + "\n"; }
    public static BusinessImportWorkbook.Template workbook(String productCode, String objectTypeCode) {
        return new BusinessImportWorkbook.Template(DOMAIN, "产情", productCode, objectTypeCode,
                XLSX_HEADERS, XLSX_LABELS);
    }

    public static BusinessImportWorkbook.Template workbook(ProductionImportDefinition definition) {
        List<ProductionImportDefinition.Field> fields = definition.groups().stream()
                .flatMap(group -> group.fields().stream())
                .filter(field -> !XLSX_CORE_HEADERS.contains(field.code()))
                .toList();
        List<String> headers = java.util.stream.Stream.of(
                        XLSX_CORE_HEADERS.stream(),
                        fields.stream().map(ProductionImportDefinition.Field::code),
                        java.util.stream.Stream.of("evidencePhotoId"))
                .flatMap(java.util.function.Function.identity()).toList();
        List<String> labels = java.util.stream.Stream.of(
                        XLSX_CORE_LABELS.stream(),
                        fields.stream().map(ProductionImportDefinition.Field::displayLabel),
                        java.util.stream.Stream.of("现场水印照片编号"))
                .flatMap(java.util.function.Function.identity()).toList();
        return new BusinessImportWorkbook.Template(DOMAIN, "产情", definition.productCode(),
                definition.objectTypeCode(), headers, labels);
    }

    public static List<List<String>> canonicalXlsx(byte[] bytes) {
        var sheet = readCompatible(bytes, XLSX_HEADERS, XLSX_LABELS, 5_000);
        java.util.ArrayList<List<String>> table = new java.util.ArrayList<>();
        table.add(XLSX_CANONICAL_HEADERS);
        for (List<String> row : sheet.rows()) {
            java.util.Map<String, String> values = new java.util.LinkedHashMap<>();
            values.put("productCode", sheet.productCode());
            values.put("objectTypeCode", sheet.objectTypeCode());
            values.put("PROD_REPORTER_NAME", "");
            for (int index = 0; index < XLSX_HEADERS.size(); index++) {
                values.put(XLSX_HEADERS.get(index), row.get(index));
            }
            table.add(XLSX_CANONICAL_HEADERS.stream().map(header -> values.getOrDefault(header, "")).toList());
        }
        return List.copyOf(table);
    }

    public static List<List<String>> canonicalXlsx(
            byte[] bytes, ProductionImportDefinition definition) {
        return canonicalXlsx(bytes, definition, 5_000);
    }

    public static List<List<String>> canonicalXlsx(
            byte[] bytes, ProductionImportDefinition definition, int maxDataRows) {
        BusinessImportWorkbook.Template template = workbook(definition);
        var sheet = readCompatible(bytes, template.headers(), template.labels(), maxDataRows);
        List<String> canonicalHeaders = java.util.stream.Stream.concat(
                java.util.stream.Stream.of("productCode", "objectTypeCode", "PROD_REPORTER_NAME"),
                template.headers().stream()).toList();
        java.util.ArrayList<List<String>> table = new java.util.ArrayList<>();
        table.add(canonicalHeaders);
        for (List<String> row : sheet.rows()) {
            java.util.Map<String, String> values = new java.util.LinkedHashMap<>();
            values.put("productCode", sheet.productCode());
            values.put("objectTypeCode", sheet.objectTypeCode());
            values.put("PROD_REPORTER_NAME", "");
            for (int index = 0; index < template.headers().size(); index++) {
                values.put(template.headers().get(index), row.get(index));
            }
            table.add(canonicalHeaders.stream().map(header -> values.getOrDefault(header, "")).toList());
        }
        return List.copyOf(table);
    }

    private static BusinessImportWorkbook.ImportSheet readCompatible(
            byte[] bytes, List<String> headers, List<String> labels, int maxDataRows) {
        try {
            return BusinessImportWorkbook.read(bytes, DOMAIN, headers, labels, maxDataRows);
        } catch (IllegalArgumentException currentFailure) {
            java.util.ArrayList<String> legacyLabels = new java.util.ArrayList<>(labels);
            legacyLabels.set(0, "所在地区代码");
            return BusinessImportWorkbook.read(bytes, DOMAIN, headers, legacyLabels, maxDataRows);
        }
    }
}
