package com.cofco.qiqihar.graintrade.production.domain;

import java.util.Map;

public record ProductionRecordQuery(
        String productCode, String pageKind, int pageNumber, int pageSize, Map<String, String> filters) {
    public ProductionRecordQuery {
        filters = Map.copyOf(filters);
    }
}
