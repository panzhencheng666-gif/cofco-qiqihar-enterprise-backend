package com.cofco.qiqihar.graintrade.formalsampleobservation.application;

import java.util.List;

public record EligibleFormalSamplePage(List<EligibleFormalSample> items, int pageNumber,
        int pageSize, long totalElements, int totalPages) {
    public EligibleFormalSamplePage {
        items = List.copyOf(items);
    }
}
