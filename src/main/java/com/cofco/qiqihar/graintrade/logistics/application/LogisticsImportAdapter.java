package com.cofco.qiqihar.graintrade.logistics.application;

import com.cofco.qiqihar.graintrade.logistics.importing.LogisticsImportDefinition;
import com.cofco.qiqihar.graintrade.logistics.importing.LogisticsImportPort;
import com.cofco.qiqihar.graintrade.logistics.importing.LogisticsImportRow;
import org.springframework.stereotype.Component;

@Component
final class LogisticsImportAdapter implements LogisticsImportPort {
    private final LogisticsService service;

    LogisticsImportAdapter(LogisticsService service) { this.service = service; }

    @Override public LogisticsImportDefinition definition(String productCode) {
        LogisticsDefinitionView definition=service.definition(productCode);
        return new LogisticsImportDefinition(definition.productCode(), definition.fields().stream().map(field ->
                new LogisticsImportDefinition.Field(field.code(),field.label(),field.unit(),field.required(),field.readOnly()))
                .toList());
    }

    @Override public void validate(LogisticsImportRow row) {
        service.validateImportDraft(new LogisticsDraft(row.productCode(), row.values()));
    }

    @Override public String importRow(LogisticsImportRow row) {
        return service.importDraft(new LogisticsDraft(row.productCode(), row.values()));
    }
}
