package com.cofco.qiqihar.graintrade.market.application;

import com.cofco.qiqihar.graintrade.evidence.application.EvidencePhotoView;
import com.cofco.qiqihar.graintrade.market.domain.MarketMonitoringRecord;
import java.util.List;
import java.util.Map;

public record MarketRecordView(
        MarketMonitoringRecord record, Map<String, String> coreValues,
        List<EvidencePhotoView> evidencePhotos, List<String> allowedActions,
        String inventoryGovernanceStatus) {
    public MarketRecordView {
        coreValues = java.util.Collections.unmodifiableMap(
                new java.util.LinkedHashMap<>(coreValues));
        evidencePhotos = List.copyOf(evidencePhotos);
        allowedActions = List.copyOf(allowedActions);
    }
}
