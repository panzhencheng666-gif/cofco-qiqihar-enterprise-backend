package com.cofco.qiqihar.graintrade.importing.application;

import com.cofco.qiqihar.graintrade.importing.infrastructure.BusinessImportWorkbook;
import com.cofco.qiqihar.graintrade.logistics.importing.LogisticsImportDefinition;
import java.util.List;

/** Product-specific logistics workbook contract derived from the authoritative field definition. */
public final class LogisticsImportTemplate {
    public static final String DOMAIN = "LOGISTICS";
    public static final String OBJECT_TYPE = "ROUTE_EVENT";

    private LogisticsImportTemplate() {}

    public static List<String> headers(LogisticsImportDefinition definition) {
        return editable(definition).stream().map(LogisticsImportDefinition.Field::code).toList();
    }

    public static List<String> labels(LogisticsImportDefinition definition) {
        return editable(definition).stream().map(field -> field.unit() == null || field.unit().isBlank()
                ? field.label() : field.label() + "（" + field.unit() + "）").toList();
    }

    public static BusinessImportWorkbook.Template workbook(LogisticsImportDefinition definition) {
        return new BusinessImportWorkbook.Template(DOMAIN, "物流", definition.productCode(), OBJECT_TYPE,
                headers(definition), labels(definition));
    }

    private static List<LogisticsImportDefinition.Field> editable(LogisticsImportDefinition definition) {
        if (definition == null) throw new IllegalArgumentException("INVALID_LOGISTICS_DEFINITION");
        return definition.fields().stream()
                .filter(field -> !field.readOnly() && !field.code().equals("LOG_REPORTER"))
                .toList();
    }
}
