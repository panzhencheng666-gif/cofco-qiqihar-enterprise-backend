package com.cofco.qiqihar.graintrade.importing.application;

import java.util.List;
import com.cofco.qiqihar.graintrade.importing.infrastructure.BusinessImportWorkbook;
import com.cofco.qiqihar.graintrade.market.importing.MarketImportDefinition;

/** Server-owned fixed columns for market monitoring imports. */
public final class MarketImportTemplate {
    public static final String DOMAIN = "MARKET";
    public static final List<String> HEADERS = List.of(
            "productCode", "objectTypeCode", "regionCode", "tradeDate",
            "purchaseBasePrice", "saleBasePrice", "carriageBoardAmount", "packagingAmount",
            "freightAmount", "packagingForm", "reporterName", "reporterPhone", "sampleName",
            "sampleContact", "latitude", "longitude", "purchaseVolume", "moisture", "evidencePhotoId");
    public static final String EVIDENCE_PHOTO_ID = "evidencePhotoId";
    private static final List<String> BUSINESS_CODES = List.of(
            "MKT_SAMPLE_NAME", "MKT_REGION", "MKT_REPORTER_NAME", "MKT_REPORTER_PHONE",
            "MKT_SAMPLE_CONTACT", "MKT_SAMPLE_LATITUDE", "MKT_SAMPLE_LONGITUDE",
            "MKT_PURCHASE_BASE_PRICE", "MKT_SALE_BASE_PRICE",
            "PURCHASE_VOLUME", "SALES_VOLUME", "MKT_CARRIAGE_BOARD_AMOUNT", "MKT_FREIGHT_AMOUNT",
            "MKT_PACKAGING_FORM", "MOISTURE", "TEST_WEIGHT", "TOXIN", "IMPURITY", "IMPERFECT_GRAIN",
            "MILDEW", "PROTEIN", "OIL_YIELD", "MILLING_YIELD", "BROWN_RICE_YIELD", "ENDING_INVENTORY");
    private static final java.util.Map<String, String> LABELS = java.util.Map.ofEntries(
            java.util.Map.entry("MKT_SAMPLE_NAME", "样本点名称"),
            java.util.Map.entry("MKT_REGION", "地区"),
            java.util.Map.entry("MKT_REPORTER_NAME", "填报人"),
            java.util.Map.entry("MKT_REPORTER_PHONE", "填报人联系方式"),
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
                java.util.stream.Stream.of("surveyYear", "surveyMonth"),
                fields.stream().map(MarketImportDefinition.Field::code)).toList();
        List<String> labels = java.util.stream.Stream.concat(
                java.util.stream.Stream.of("数据年份", "数据月份"),
                fields.stream().map(MarketImportTemplate::displayLabel)).toList();
        return new BusinessImportWorkbook.Template(DOMAIN, "市场", definition.productCode(),
                definition.objectTypeCode(), headers, labels);
    }

    public static List<List<String>> canonicalXlsx(byte[] bytes, MarketImportDefinition definition) {
        return canonicalXlsx(bytes, definition, 5_000);
    }

    public static List<List<String>> canonicalXlsx(
            byte[] bytes, MarketImportDefinition definition, int maxDataRows) {
        BusinessImportWorkbook.Template template = workbook(definition);
        var sheet = BusinessImportWorkbook.read(bytes, DOMAIN, template.headers(), template.labels(), maxDataRows);
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

    private static String displayLabel(MarketImportDefinition.Field field) {
        String label = LABELS.getOrDefault(field.code(), field.label());
        return field.unit() == null || field.unit().isBlank() ? label : label + "（" + field.unit() + "）";
    }
}
