package com.cofco.qiqihar.graintrade.market.application;

import com.cofco.qiqihar.graintrade.market.domain.MarketRecordQuery;
import com.cofco.qiqihar.graintrade.market.domain.MarketStatus;
import com.cofco.qiqihar.graintrade.market.importing.MarketReturnedCorrectionPort;
import com.cofco.qiqihar.graintrade.market.importing.MarketReturnedCorrectionRecord;
import com.cofco.qiqihar.graintrade.market.importing.MarketImportRow;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public final class MarketReturnedCorrectionAdapter implements MarketReturnedCorrectionPort {
    private static final String COORDINATE_REASON = "地区与经纬度不匹配";
    private static final int PAGE_SIZE = 20;
    private final MarketMonitoringService service;

    public MarketReturnedCorrectionAdapter(MarketMonitoringService service) {
        this.service = service;
    }

    @Override
    public List<MarketReturnedCorrectionRecord> returned(String productCode) {
        ArrayList<MarketReturnedCorrectionRecord> records = new ArrayList<>();
        for (int pageNumber = 0;; pageNumber++) {
            var page = service.listLifecycle(new MarketRecordQuery(
                    productCode, "MONITORING", pageNumber, PAGE_SIZE,
                    Map.of("status", MarketStatus.RETURNED.name())));
            page.items().forEach(item -> addIfEligible(records, productCode, item));
            if (pageNumber + 1 >= page.totalPages()) break;
        }
        return List.copyOf(records);
    }

    @Override
    public String correctAndSubmit(String originalId, long originalVersion, MarketImportRow row) {
        MarketMonitoringDraft draft = new MarketMonitoringDraft(
                row.productCode(), row.coreValues(), row.facts(), row.evidencePhotoIds());
        service.validateReturnedCorrection(originalId, originalVersion, draft);
        MarketRecordView saved = service.save(originalId, originalVersion, draft);
        MarketRecordView submitted = service.submit(originalId, saved.record().version());
        return submitted.record().id();
    }

    private void addIfEligible(
            List<MarketReturnedCorrectionRecord> records, String productCode, MarketListItem item) {
        MarketRecordView view = service.detail(item.id());
        var record = view.record();
        if (!view.allowedActions().containsAll(List.of("SAVE", "SUBMIT"))
                || record.status() != MarketStatus.RETURNED
                || !productCode.equals(record.productCode())
                || record.returnReason() == null
                || !COORDINATE_REASON.equals(record.returnReason().trim())) {
            return;
        }
        Map<String, String> facts = new LinkedHashMap<>();
        record.facts().forEach((code, value) -> facts.put(code, value.toPlainString()));
        int surveyYear = integer(item.values().get("MKT_SURVEY_YEAR"), record.tradeDate().getYear());
        Integer surveyMonth = optionalInteger(item.values().get("MKT_SURVEY_MONTH"));
        records.add(new MarketReturnedCorrectionRecord(
                record.id(), record.productCode(), record.objectTypeCode(), record.regionCode(),
                record.tradeDate(), surveyYear, surveyMonth, record.version(), view.coreValues(), facts));
    }

    private static int integer(String value, int fallback) {
        return value == null || value.isBlank() ? fallback : Integer.parseInt(value);
    }

    private static Integer optionalInteger(String value) {
        return value == null || value.isBlank() ? null : Integer.valueOf(value);
    }
}
