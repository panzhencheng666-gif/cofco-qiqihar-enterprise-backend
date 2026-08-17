package com.cofco.qiqihar.graintrade.importing.application;

import com.cofco.qiqihar.graintrade.importing.infrastructure.BusinessImportWorkbook;
import com.cofco.qiqihar.graintrade.logistics.importing.LogisticsImportDefinition;
import java.util.List;

/** Product-specific logistics workbook contract derived from the authoritative field definition. */
public final class LogisticsImportTemplate {
    public static final String DOMAIN = "LOGISTICS";
    public static final String OBJECT_TYPE = "ROUTE_EVENT";

    private LogisticsImportTemplate() {}

    private static final List<String> PUBLIC_WORKBOOK_CODES = List.of(
            "surveyYear", "surveyMonth", "fillingDate", "LOG_SAMPLE_NAME", "LOG_REGION",
            "LOG_REPORTER",
            "LOG_REPORTER_PHONE", "LOG_SAMPLE_CONTACT", "LOG_SAMPLE_LATITUDE", "LOG_SAMPLE_LONGITUDE",
            "LOG_TRANSPORT_MODE", "LOG_DIRECTION", "LOG_ROUTE_VOLUME", "LOG_FREIGHT_RATE", "LOG_BOARD_PRICE",
            "LOG_STATUS");

    public static List<String> headers(LogisticsImportDefinition definition) {
        return labels(definition);
    }

    public static List<String> codes(LogisticsImportDefinition definition) {
        return java.util.stream.Stream.concat(
                workbookFields(definition).stream().map(LogisticsImportDefinition.Field::code),
                java.util.stream.Stream.of(BusinessImportWorkbook.PHOTO_FILENAMES_CODE)).toList();
    }

    public static List<String> labels(LogisticsImportDefinition definition) {
        return java.util.stream.Stream.concat(
                workbookFields(definition).stream().map(field -> field.unit() == null || field.unit().isBlank()
                        ? field.label() : field.label() + "（" + field.unit() + "）"),
                java.util.stream.Stream.of(BusinessImportWorkbook.PHOTO_FILENAMES_LABEL)).toList();
    }

    public static BusinessImportWorkbook.Template workbook(LogisticsImportDefinition definition) {
        return workbook(null, definition);
    }

    public static BusinessImportWorkbook.Template workbook(
            String productCode, LogisticsImportDefinition definition) {
        List<LogisticsImportDefinition.Field> fields = workbookFields(definition);
        return new BusinessImportWorkbook.Template(DOMAIN, "物流", productCode, null,
                BusinessImportWorkbook.CONTRACT_VERSION, null, headers(definition), labels(definition),
                java.util.stream.Stream.concat(fields.stream().map(field ->
                                new BusinessImportWorkbook.ColumnRule(
                                        displayLabel(field),
                                        field.controlType().contains("DATE") ? "DATE"
                                                : field.controlType().equals("DECIMAL") ? "DECIMAL" : "TEXT",
                                        field.controlType(),
                                        "LOG_SAMPLE_NAME".equals(field.code()) || "LOG_REGION".equals(field.code()),
                                        options(field, productCode != null),
                                        field.precision() == null ? 0 : field.precision(),
                                        field.scale() == null ? -1 : field.scale(), description(field.code()))),
                        java.util.stream.Stream.of(BusinessImportWorkbook.photoFilenameRule(
                                BusinessImportWorkbook.PHOTO_FILENAMES_LABEL))).toList());
    }

    private static List<LogisticsImportDefinition.Field> workbookFields(LogisticsImportDefinition definition) {
        if (definition == null) throw new IllegalArgumentException("INVALID_LOGISTICS_DEFINITION");
        java.util.Map<String, LogisticsImportDefinition.Field> fieldsByCode = new java.util.LinkedHashMap<>();
        definition.fields().stream()
                .filter(field -> PUBLIC_WORKBOOK_CODES.contains(field.code()))
                .forEach(field -> {
                    if (fieldsByCode.putIfAbsent(field.code(), field) != null) {
                        throw new IllegalArgumentException("INVALID_LOGISTICS_DEFINITION");
                    }
                });
        if (fieldsByCode.size() != PUBLIC_WORKBOOK_CODES.size()) {
            throw new IllegalArgumentException("INVALID_LOGISTICS_DEFINITION");
        }
        return PUBLIC_WORKBOOK_CODES.stream().map(fieldsByCode::get).toList();
    }

    private static String displayLabel(LogisticsImportDefinition.Field field) {
        return field.unit() == null || field.unit().isBlank()
                ? field.label() : field.label() + "（" + field.unit() + "）";
    }

    private static String description(String code) {
        return switch (code) {
            case "surveyYear" -> "可留空；填写时为 1900—2200";
            case "surveyMonth" -> "可留空表示年度数据";
            case "fillingDate", "LOG_REPORTER", "LOG_STATUS" -> "系统生成，请勿填写或覆盖";
            case "LOG_TRANSPORT_MODE" -> "业务运输方式，只能选择铁路或公路";
            case "LOG_FREIGHT_RATE" -> "不含车板价";
            case "LOG_BOARD_PRICE" -> "独立车板价，不与物流运价相加或推导";
            case "LOG_SAMPLE_LATITUDE" -> "范围 -90 至 90";
            case "LOG_SAMPLE_LONGITUDE" -> "范围 -180 至 180";
            default -> null;
        };
    }

    private static List<String> options(LogisticsImportDefinition.Field field, boolean publicProductWorkbook) {
        if (!publicProductWorkbook || !"SELECT".equals(field.controlType())
                || "LOG_REGION".equals(field.code()) || field.options().size() > 10) return List.of();
        List<String> labels = field.options().stream().map(LogisticsImportDefinition.Option::label).toList();
        if ("LOG_TRANSPORT_MODE".equals(field.code()) && !labels.equals(List.of("铁路", "公路"))) {
            throw new IllegalArgumentException("INVALID_LOGISTICS_TRANSPORT_MODES");
        }
        return labels;
    }
}
