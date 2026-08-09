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

    private MarketImportTemplate() {}

    public static String csv() { return String.join(",", HEADERS) + "\n"; }
    public static BusinessImportWorkbook.Template workbook(MarketImportDefinition definition) {
        List<MarketImportDefinition.Field> fields = editableFields(definition);
        List<String> headers = java.util.stream.Stream.concat(
                fields.stream().map(MarketImportDefinition.Field::code),
                java.util.stream.Stream.of(EVIDENCE_PHOTO_ID)).toList();
        List<String> labels = java.util.stream.Stream.concat(
                fields.stream().map(MarketImportDefinition.Field::displayLabel),
                java.util.stream.Stream.of("现场水印照片编号")).toList();
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
        return java.util.stream.Stream.concat(definition.coreFields().stream()
                        .filter(field -> !field.readOnly())
                        .filter(field -> !field.code().equals("MKT_OBJECT_TYPE"))
                        .filter(field -> !field.code().equals("MKT_REPORTER_NAME")),
                definition.factFields().stream()).toList();
    }
}
