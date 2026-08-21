package com.cofco.qiqihar.graintrade.importing.application;

import java.util.List;
import com.cofco.qiqihar.graintrade.importing.application.BusinessImportTemplateCatalog.ObjectTypeOption;
import com.cofco.qiqihar.graintrade.importing.infrastructure.BusinessImportWorkbook;
import com.cofco.qiqihar.graintrade.market.importing.MarketImportDefinition;

/** Server-owned fixed columns for market monitoring imports. */
public final class MarketImportTemplate {
    public static final String DOMAIN = "MARKET";
    private static final java.util.Map<String, String> PRIOR_PRODUCT_CONTRACT_DIGESTS = java.util.Map.of(
            "CORN", "sha256:5fc9a7e9a33f66ca596e021c232d6f74da2dbd812e3e60bbb5f0a296f853ef70",
            "SOYBEAN", "sha256:c533fb002a51e8be2307287f51c90ca2423c308f78ca1450a7f3ff0d9096fd83",
            "RICE", "sha256:37a9e138632a9da63be9b7b545209099011fb52a3e0bf05a7349f3b4ae12cd1c");
    public static final List<String> HEADERS = List.of(
            "productCode", "objectTypeCode", "regionCode", "tradeDate",
            "purchaseBasePrice", "saleBasePrice", "carriageBoardAmount", "packagingAmount",
            "freightAmount", "packagingForm", "reporterName", "reporterPhone", "sampleName",
            "sampleContact", "latitude", "longitude", "purchaseVolume", "moisture", "evidencePhotoId");
    public static final String EVIDENCE_PHOTO_ID = "evidencePhotoId";
    private static final List<String> BUSINESS_CODES = List.of(
            "MKT_SAMPLE_NAME", "MKT_REGION", "MKT_SURVEYOR_NAME", "MKT_SURVEYOR_PHONE",
            "MKT_SAMPLE_CONTACT", "MKT_SAMPLE_LATITUDE", "MKT_SAMPLE_LONGITUDE",
            "MKT_PURCHASE_BASE_PRICE", "MKT_SALE_BASE_PRICE",
            "PURCHASE_VOLUME", "SALES_VOLUME", "MKT_CARRIAGE_BOARD_AMOUNT", "MKT_FREIGHT_AMOUNT",
            "MKT_PACKAGING_FORM", "MOISTURE", "TEST_WEIGHT", "TOXIN", "IMPURITY", "IMPERFECT_GRAIN",
            "MILDEW", "PROTEIN", "OIL_YIELD", "MILLING_YIELD", "BROWN_RICE_YIELD", "ENDING_INVENTORY");
    private static final java.util.Map<String, String> LABELS = java.util.Map.ofEntries(
            java.util.Map.entry("MKT_SAMPLE_NAME", "样本点名称"),
            java.util.Map.entry("MKT_REGION", "地区"),
            java.util.Map.entry("MKT_REPORTER_NAME", "填报人"),
            java.util.Map.entry("MKT_SURVEYOR_NAME", "调研人"),
            java.util.Map.entry("MKT_SURVEYOR_PHONE", "调研人联系方式"),
            java.util.Map.entry("MKT_SAMPLE_CONTACT", "样本点联系方式"),
            java.util.Map.entry("MKT_SAMPLE_LATITUDE", "纬度"),
            java.util.Map.entry("MKT_SAMPLE_LONGITUDE", "经度"),
            java.util.Map.entry("MKT_PURCHASE_BASE_PRICE", "采集对象收购价格"),
            java.util.Map.entry("MKT_SALE_BASE_PRICE", "采集对象销售价格"),
            java.util.Map.entry("ENDING_INVENTORY", "现有库存"));

    private MarketImportTemplate() {}

    public static String csv() { return String.join(",", HEADERS) + "\n"; }
    public static BusinessImportWorkbook.Template workbook(MarketImportDefinition definition) {
        List<MarketImportDefinition.Field> fields = editableFields(definition);
        List<String> headers = java.util.stream.Stream.concat(
                java.util.stream.Stream.concat(
                        java.util.stream.Stream.of("surveyYear", "surveyMonth"),
                        fields.stream().map(MarketImportDefinition.Field::code)),
                java.util.stream.Stream.of(BusinessImportWorkbook.PHOTO_FILENAMES_CODE)).toList();
        List<String> labels = java.util.stream.Stream.concat(
                java.util.stream.Stream.concat(
                        java.util.stream.Stream.of("数据年份", "数据月份"),
                        fields.stream().map(MarketImportTemplate::displayLabel)),
                java.util.stream.Stream.of(BusinessImportWorkbook.PHOTO_FILENAMES_LABEL)).toList();
        List<BusinessImportWorkbook.ColumnRule> rules = java.util.stream.Stream.concat(
                java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(
                                new BusinessImportWorkbook.ColumnRule(
                                        "surveyYear", "TEXT", "YEAR", true, List.of(), 4, 0,
                                        "必填，填写 1900—2200 的整数"),
                                new BusinessImportWorkbook.ColumnRule(
                                        "surveyMonth", "TEXT", "MONTH", false, List.of(), 2, 0,
                                        "可留空；填写时为 1—12 月")),
                        fields.stream().map(field -> columnRule(field, false))),
                java.util.stream.Stream.of(BusinessImportWorkbook.photoFilenameRule(
                        BusinessImportWorkbook.PHOTO_FILENAMES_CODE))).toList();
        return new BusinessImportWorkbook.Template(DOMAIN, "市场", definition.productCode(),
                definition.objectTypeCode(), BusinessImportWorkbook.CONTRACT_VERSION, null,
                headers, labels, rules);
    }

    public static List<List<String>> canonicalXlsx(byte[] bytes, MarketImportDefinition definition) {
        return canonicalXlsx(bytes, definition, 5_000);
    }

    public static BusinessImportWorkbook.Template productWorkbook(
            String productCode, List<MarketImportDefinition> definitions,
            List<ObjectTypeOption> objectTypes) {
        List<MarketImportDefinition.Field> fields = productFields(productCode, definitions);
        List<String> headers = java.util.stream.Stream.of(
                        java.util.stream.Stream.of("objectTypeCode", "surveyYear", "surveyMonth"),
                        fields.stream().map(MarketImportDefinition.Field::code),
                        java.util.stream.Stream.of(BusinessImportWorkbook.PHOTO_FILENAMES_CODE))
                .flatMap(java.util.function.Function.identity()).toList();
        List<String> labels = java.util.stream.Stream.of(
                        java.util.stream.Stream.of("样本点类型", "数据年份", "数据月份"),
                        fields.stream().map(MarketImportTemplate::displayLabel),
                        java.util.stream.Stream.of(BusinessImportWorkbook.PHOTO_FILENAMES_LABEL))
                .flatMap(java.util.function.Function.identity()).toList();
        List<BusinessImportWorkbook.ColumnRule> rules = java.util.stream.Stream.of(
                        java.util.stream.Stream.of(
                                new BusinessImportWorkbook.ColumnRule(
                                        "objectTypeCode", "TEXT", "SELECT", true,
                                        objectTypes.stream().map(ObjectTypeOption::label).toList(), 0, 0,
                                        "必填，请从下拉选项中选择样本点类型"),
                                new BusinessImportWorkbook.ColumnRule(
                                        "surveyYear", "TEXT", "YEAR", true, List.of(), 4, 0,
                                        "必填，填写 1900—2200 的整数"),
                                new BusinessImportWorkbook.ColumnRule(
                                        "surveyMonth", "TEXT", "MONTH", false, List.of(), 2, 0,
                                        "可留空；填写时为 1—12 月")),
                        fields.stream().map(field -> columnRule(field, true)),
                        java.util.stream.Stream.of(BusinessImportWorkbook.photoFilenameRule(
                                BusinessImportWorkbook.PHOTO_FILENAMES_CODE)))
                .flatMap(java.util.function.Function.identity()).toList();
        return new BusinessImportWorkbook.Template(DOMAIN, "市场", productCode, null,
                BusinessImportWorkbook.CONTRACT_VERSION, null, headers, labels, rules);
    }

    public static List<BusinessImportWorkbook.Template> compatiblePriorProductWorkbooks(
            BusinessImportWorkbook.Template current) {
        String digest = PRIOR_PRODUCT_CONTRACT_DIGESTS.get(current.productCode());
        if (digest == null) return List.of();
        return List.of(new BusinessImportWorkbook.Template(
                current.domainCode(), current.domainLabel(), current.productCode(), current.objectTypeCode(),
                current.contractVersion(), digest, current.headers(), current.labels(), current.rules()));
    }

    public static List<String> productCodes(
            String productCode, List<MarketImportDefinition> definitions) {
        return java.util.stream.Stream.of(
                        java.util.stream.Stream.of("objectTypeCode", "surveyYear", "surveyMonth"),
                        productFields(productCode, definitions).stream().map(MarketImportDefinition.Field::code),
                        java.util.stream.Stream.of(BusinessImportWorkbook.PHOTO_FILENAMES_CODE))
                .flatMap(java.util.function.Function.identity()).toList();
    }

    public static List<List<String>> canonicalXlsx(
            byte[] bytes, MarketImportDefinition definition, int maxDataRows) {
        BusinessImportWorkbook.Template template = workbook(definition);
        var sheet = BusinessImportWorkbook.read(bytes, template, maxDataRows);
        java.util.ArrayList<List<String>> table = new java.util.ArrayList<>();
        List<String> headers = java.util.stream.Stream.concat(
                java.util.stream.Stream.of("productCode", "objectTypeCode"),
                template.headers().stream()).toList();
        table.add(headers);
        for (List<String> row : sheet.rows()) {
            java.util.Map<String, String> values = new java.util.LinkedHashMap<>();
            values.put("productCode", sheet.productCode());
            values.put("objectTypeCode", sheet.objectTypeCode());
            for (int index = 0; index < template.headers().size(); index++) {
                values.put(template.headers().get(index), row.get(index));
            }
            table.add(headers.stream().map(header -> values.getOrDefault(header, "")).toList());
        }
        return List.copyOf(table);
    }

    public static List<MarketImportDefinition.Field> editableFields(MarketImportDefinition definition) {
        java.util.Map<String, MarketImportDefinition.Field> byCode = java.util.stream.Stream
                .concat(definition.coreFields().stream(), definition.factFields().stream())
                .collect(java.util.stream.Collectors.toMap(MarketImportDefinition.Field::code, field -> field));
        return BUSINESS_CODES.stream().map(byCode::get).filter(java.util.Objects::nonNull)
                .filter(field -> !field.readOnly()).toList();
    }

    private static List<MarketImportDefinition.Field> productFields(
            String productCode, List<MarketImportDefinition> definitions) {
        if (definitions == null || definitions.isEmpty()
                || definitions.stream().anyMatch(definition -> !productCode.equals(definition.productCode()))) {
            throw new IllegalArgumentException("INVALID_MARKET_PRODUCT_TEMPLATE");
        }
        java.util.Map<String, MarketImportDefinition.Field> fields = new java.util.LinkedHashMap<>();
        definitions.forEach(definition -> editableFields(definition).forEach(field ->
                fields.putIfAbsent(field.code(), field)));
        return BUSINESS_CODES.stream().map(fields::get).filter(java.util.Objects::nonNull).toList();
    }

    private static String displayLabel(MarketImportDefinition.Field field) {
        String label = LABELS.getOrDefault(field.code(), field.label());
        return field.unit() == null || field.unit().isBlank() ? label : label + "（" + field.unit() + "）";
    }

    private static BusinessImportWorkbook.ColumnRule columnRule(
            MarketImportDefinition.Field field, boolean publicProductWorkbook) {
        boolean required = "MKT_SAMPLE_NAME".equals(field.code()) || "MKT_REGION".equals(field.code());
        String valueType = field.controlType().contains("DECIMAL") ? "DECIMAL"
                : field.controlType().contains("DATE") ? "DATE" : "TEXT";
        return new BusinessImportWorkbook.ColumnRule(field.code(), valueType, field.controlType(), required,
                options(field, publicProductWorkbook), field.precision() == null ? 0 : field.precision(),
                field.scale() == null ? -1 : field.scale(), null);
    }

    private static List<String> options(
            MarketImportDefinition.Field field, boolean publicProductWorkbook) {
        if (!publicProductWorkbook || !"SELECT".equals(field.controlType())
                || "MKT_REGION".equals(field.code()) || field.options().size() > 10) return List.of();
        return field.options().stream().map(MarketImportDefinition.Option::label).toList();
    }
}
