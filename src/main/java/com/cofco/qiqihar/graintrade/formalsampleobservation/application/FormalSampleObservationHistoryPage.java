package com.cofco.qiqihar.graintrade.formalsampleobservation.application;

import java.util.List;

public record FormalSampleObservationHistoryPage(
        List<FormalSampleObservationHistoryItem> items,
        long totalElements,
        int pageNumber,
        int pageSize) {
    public FormalSampleObservationHistoryPage {
        items = List.copyOf(items);
    }
}
