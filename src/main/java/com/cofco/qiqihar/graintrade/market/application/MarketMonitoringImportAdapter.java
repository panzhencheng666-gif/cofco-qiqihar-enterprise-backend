package com.cofco.qiqihar.graintrade.market.application;

import com.cofco.qiqihar.graintrade.market.importing.MarketImportPort;
import com.cofco.qiqihar.graintrade.market.importing.MarketImportRow;
import org.springframework.stereotype.Component;

@Component
final class MarketMonitoringImportAdapter implements MarketImportPort {
    private final MarketMonitoringService service;

    MarketMonitoringImportAdapter(MarketMonitoringService service) {
        this.service = service;
    }

    @Override
    public void validate(MarketImportRow row) {
        service.validateImportDraft(toDraft(row));
    }

    @Override
    public String importRow(MarketImportRow row) {
        return service.importDraft(toDraft(row));
    }

    private static MarketMonitoringDraft toDraft(MarketImportRow row) {
        return new MarketMonitoringDraft(
                row.productCode(), row.coreValues(), row.facts(), row.evidencePhotoIds());
    }
}
