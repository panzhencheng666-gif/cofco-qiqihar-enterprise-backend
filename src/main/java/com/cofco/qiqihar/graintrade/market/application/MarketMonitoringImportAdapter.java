package com.cofco.qiqihar.graintrade.market.application;

import com.cofco.qiqihar.graintrade.market.importing.MarketImportPort;
import com.cofco.qiqihar.graintrade.market.importing.MarketImportDefinition;
import com.cofco.qiqihar.graintrade.market.importing.MarketImportRow;
import org.springframework.stereotype.Component;

@Component
final class MarketMonitoringImportAdapter implements MarketImportPort {
    private final MarketMonitoringService service;

    MarketMonitoringImportAdapter(MarketMonitoringService service) {
        this.service = service;
    }

    @Override
    public MarketImportDefinition definition(String productCode, String objectTypeCode) {
        MarketFormDefinition definition = service.definition(productCode, objectTypeCode);
        return new MarketImportDefinition(
                definition.productCode(),
                definition.objectTypeCode(),
                definition.coreFields().stream().map(field -> new MarketImportDefinition.Field(
                        field.code(), field.label(), field.controlType(), field.unit(), field.required(),
                        field.precision(), field.scale(), field.options().stream()
                                .map(option -> new MarketImportDefinition.Option(
                                        option.value(), option.label())).toList())).toList(),
                definition.groups().stream().flatMap(group -> group.fields().stream())
                        .map(field -> new MarketImportDefinition.Field(
                                field.code(), field.label(), field.valueType(), field.unit(), true,
                                field.precision(), field.scale(), java.util.List.of()))
                        .toList());
    }

    @Override
    public void validate(MarketImportRow row) {
        service.validateImportDraft(toDraft(row));
    }

    @Override
    public String importRow(MarketImportRow row) {
        return service.importDraft(toDraft(row));
    }

    @Override
    public String importAndSubmit(MarketImportRow row) {
        String id = service.importDraft(toDraft(row));
        service.submit(id, 0);
        return id;
    }

    private static MarketMonitoringDraft toDraft(MarketImportRow row) {
        return new MarketMonitoringDraft(
                row.productCode(), row.coreValues(), row.facts(), row.evidencePhotoIds());
    }
}
