package com.cofco.qiqihar.graintrade.importing.application;

import java.util.List;
import com.cofco.qiqihar.graintrade.importing.application.BusinessImportTemplateCatalog.ObjectTypeOption;
import com.cofco.qiqihar.graintrade.importing.infrastructure.BusinessImportWorkbook;
import com.cofco.qiqihar.graintrade.production.application.ProductionImportDefinition;
import com.cofco.qiqihar.graintrade.production.application.ProductionSurveyField;

public final class ProductionImportTemplate {
    public static final String DOMAIN = "PRODUCTION";
    public static final List<String> SUBMISSION_METADATA_HEADERS = List.of(
            "PROD_REPORTER_NAME", "PROD_REPORTER_PHONE", "PROD_SAMPLE_CONTACT",
            "PROD_SAMPLE_LATITUDE", "PROD_SAMPLE_LONGITUDE");
    private static final List<String> BUSINESS_HEADERS = List.of(
            "surveyYear", "surveyMonth", "PROD_SAMPLE_NAME", "regionCode", "PROD_CULTIVAR_NAME",
            "PROD_REPORTER_NAME",
            "PROD_REPORTER_PHONE", "PROD_SAMPLE_CONTACT", "PROD_SAMPLE_LATITUDE", "PROD_SAMPLE_LONGITUDE",
            "cultivatedAreaMu", "PROD_HARVEST_AREA_MU", "PROD_AFFECTED_AREA_MU", "PROD_GROWTH_STATUS",
            "PROD_GROWTH_STAGE", "yieldPerMuKilograms", "MOISTURE", "TEST_WEIGHT", "TOXIN", "IMPURITY",
            "IMPERFECT_GRAIN", "MILDEW", "PROTEIN", "OIL_YIELD", "MILLING_YIELD", "BROWN_RICE_YIELD",
            "PROD_OPENING_INVENTORY", "PROD_SALES_VOLUME", "PROD_SELF_USE", "PROD_ENDING_INVENTORY",
            "PROD_INTENDED_AREA_MU", "PROD_INTENTION_REASON", "LAND_RENT", "SEED_COST", "PESTICIDE_COST",
            "FERTILIZER_COST", "IRRIGATION_COST", "LABOR_COST", "MACHINERY_COST", "OTHER_COST",
            "SUBSIDY_AMOUNT", "INSURANCE_AMOUNT", "PROD_SOURCE_NOTE");
    public static final List<String> DETAIL_HEADERS = List.of("PROD_CULTIVAR_NAME", "PROD_SAMPLE_NAME", "PROD_HARVEST_AREA_MU",
            "PROD_AFFECTED_AREA_MU", "PROD_GROWTH_STATUS", "PROD_GROWTH_STAGE", "PROD_OPENING_INVENTORY",
            "PROD_SALES_VOLUME", "PROD_SELF_USE", "PROD_ENDING_INVENTORY", "PROD_INTENDED_AREA_MU",
            "PROD_INTENTION_REASON", "PROD_SURPLUS_SUBJECT_CODE", "PROD_SURPLUS_CUTOFF_DATE");
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
            "INSURANCE_AMOUNT", "SUBSIDY_AMOUNT", BusinessImportWorkbook.PHOTO_FILENAMES_CODE);
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
            "人工费用（元/亩）", "机耕费用（元/亩）", "其他成本（元/亩）", "保险金额（元）", "补贴金额（元）",
            BusinessImportWorkbook.PHOTO_FILENAMES_LABEL);
    public static final List<String> XLSX_CORE_LABELS = XLSX_LABELS.subList(0, 9);
    private ProductionImportTemplate() {}
    public static String csv() { return String.join(",", HEADERS) + "\n"; }
    public static BusinessImportWorkbook.Template workbook(String productCode, String objectTypeCode) {
        return new BusinessImportWorkbook.Template(DOMAIN, "产情", productCode, objectTypeCode,
                BusinessImportWorkbook.CONTRACT_VERSION, null,
                XLSX_HEADERS, XLSX_LABELS, List.of());
    }

    public static BusinessImportWorkbook.Template workbook(ProductionImportDefinition definition) {
        if (!definition.fields().isEmpty()) {
            List<ProductionSurveyField> fields = auditedFields(definition);
            List<String> labels = java.util.stream.Stream.concat(
                    fields.stream().map(ProductionSurveyField::displayLabel),
                    java.util.stream.Stream.of(BusinessImportWorkbook.PHOTO_FILENAMES_LABEL)).toList();
            List<BusinessImportWorkbook.ColumnRule> rules = java.util.stream.Stream.concat(
                    fields.stream().map(field -> columnRule(field, field.displayLabel())),
                    java.util.stream.Stream.of(BusinessImportWorkbook.photoFilenameRule(
                            BusinessImportWorkbook.PHOTO_FILENAMES_LABEL))).toList();
            return new BusinessImportWorkbook.Template(DOMAIN, "产情", definition.productCode(),
                    definition.objectTypeCode(), BusinessImportWorkbook.CONTRACT_VERSION, null,
                    labels, labels, rules);
        }
        List<ProductionImportDefinition.Field> fields = definition.groups().stream()
                .flatMap(group -> group.fields().stream())
                .filter(field -> !XLSX_CORE_HEADERS.contains(field.code()))
                .toList();
        List<String> headers = java.util.stream.Stream.of(
                        XLSX_CORE_HEADERS.stream(),
                        fields.stream().map(ProductionImportDefinition.Field::code),
                        java.util.stream.Stream.of(BusinessImportWorkbook.PHOTO_FILENAMES_CODE))
                .flatMap(java.util.function.Function.identity()).toList();
        List<String> labels = java.util.stream.Stream.of(
                        XLSX_CORE_LABELS.stream(),
                        fields.stream().map(ProductionImportDefinition.Field::displayLabel),
                        java.util.stream.Stream.of(BusinessImportWorkbook.PHOTO_FILENAMES_LABEL))
                .flatMap(java.util.function.Function.identity()).toList();
        return new BusinessImportWorkbook.Template(DOMAIN, "产情", definition.productCode(),
                definition.objectTypeCode(), BusinessImportWorkbook.CONTRACT_VERSION, null,
                headers, labels, List.of());
    }

    public static BusinessImportWorkbook.Template productWorkbook(
            String productCode, List<ProductionImportDefinition> definitions,
            List<ObjectTypeOption> objectTypes) {
        List<ProductionSurveyField> fields = productFields(productCode, definitions);
        List<String> headers = java.util.stream.Stream.of(
                        java.util.stream.Stream.of("样本点类型"),
                        fields.stream().map(ProductionSurveyField::displayLabel),
                        java.util.stream.Stream.of(BusinessImportWorkbook.PHOTO_FILENAMES_LABEL))
                .flatMap(java.util.function.Function.identity()).toList();
        List<BusinessImportWorkbook.ColumnRule> rules = java.util.stream.Stream.of(
                        java.util.stream.Stream.of(new BusinessImportWorkbook.ColumnRule(
                                "样本点类型", "TEXT", "SELECT", false,
                                objectTypes.stream().map(ObjectTypeOption::label).toList(), 0, 0,
                                "可留空导入草稿；提升为正式记录前补充")),
                        fields.stream().map(field -> columnRule(field, field.displayLabel())),
                        java.util.stream.Stream.of(BusinessImportWorkbook.photoFilenameRule(
                                BusinessImportWorkbook.PHOTO_FILENAMES_LABEL)))
                .flatMap(java.util.function.Function.identity()).toList();
        return new BusinessImportWorkbook.Template(DOMAIN, "产情", productCode, null,
                BusinessImportWorkbook.CONTRACT_VERSION, null, headers, headers, rules);
    }

    public static List<String> productCodes(
            String productCode, List<ProductionImportDefinition> definitions) {
        return java.util.stream.Stream.of(
                        java.util.stream.Stream.of("objectTypeCode"),
                        productFields(productCode, definitions).stream().map(ProductionSurveyField::code),
                        java.util.stream.Stream.of(BusinessImportWorkbook.PHOTO_FILENAMES_CODE))
                .flatMap(java.util.function.Function.identity()).toList();
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
        List<String> codes = codes(definition);
        var sheet = definition.fields().isEmpty()
                ? readCompatible(bytes, template.headers(), template.labels(), maxDataRows)
                : BusinessImportWorkbook.read(bytes, template, maxDataRows);
        List<String> canonicalHeaders = java.util.stream.Stream.concat(
                java.util.stream.Stream.of("productCode", "objectTypeCode"),
                codes.stream()).toList();
        java.util.ArrayList<List<String>> table = new java.util.ArrayList<>();
        table.add(canonicalHeaders);
        for (List<String> row : sheet.rows()) {
            java.util.Map<String, String> values = new java.util.LinkedHashMap<>();
            values.put("productCode", sheet.productCode());
            values.put("objectTypeCode", sheet.objectTypeCode());
            values.put("PROD_REPORTER_NAME", "");
            for (int index = 0; index < codes.size(); index++) {
                values.put(codes.get(index), row.get(index));
            }
            table.add(canonicalHeaders.stream().map(header -> values.getOrDefault(header, "")).toList());
        }
        return List.copyOf(table);
    }

    public static List<String> codes(ProductionImportDefinition definition) {
        if (definition.fields().isEmpty()) return workbook(definition).headers();
        return java.util.stream.Stream.concat(
                auditedFields(definition).stream().map(ProductionSurveyField::code),
                java.util.stream.Stream.of(BusinessImportWorkbook.PHOTO_FILENAMES_CODE)).toList();
    }

    private static List<ProductionSurveyField> auditedFields(ProductionImportDefinition definition) {
        java.util.Map<String, ProductionSurveyField> byCode = definition.fields().stream()
                .collect(java.util.stream.Collectors.toMap(ProductionSurveyField::code, field -> field));
        return BUSINESS_HEADERS.stream()
                .map(byCode::get).filter(java.util.Objects::nonNull)
                .filter(field -> field.importable() || "PROD_REPORTER_NAME".equals(field.code())).toList();
    }

    private static List<ProductionSurveyField> productFields(
            String productCode, List<ProductionImportDefinition> definitions) {
        if (definitions == null || definitions.isEmpty()
                || definitions.stream().anyMatch(definition -> !productCode.equals(definition.productCode()))) {
            throw new IllegalArgumentException("INVALID_PRODUCTION_PRODUCT_TEMPLATE");
        }
        java.util.Map<String, ProductionSurveyField> fields = new java.util.LinkedHashMap<>();
        definitions.forEach(definition -> auditedFields(definition).forEach(field ->
                fields.putIfAbsent(field.code(), field)));
        return BUSINESS_HEADERS.stream().map(fields::get).filter(java.util.Objects::nonNull).toList();
    }

    private static BusinessImportWorkbook.ColumnRule columnRule(
            ProductionSurveyField field, String publicColumnName) {
        if ("PROD_REPORTER_NAME".equals(field.code())) {
            return new BusinessImportWorkbook.ColumnRule(
                    publicColumnName, field.valueType(), field.controlType(), false,
                    field.options(), field.precision(), field.scale(),
                    "由当前登录人员自动记录；模板中可留空，导入值不会覆盖登录身份");
        }
        boolean required = "PROD_SAMPLE_NAME".equals(field.code()) || "regionCode".equals(field.code());
        return new BusinessImportWorkbook.ColumnRule(publicColumnName, field.valueType(), field.controlType(),
                required, field.options(), field.precision(), field.scale(), field.description());
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
