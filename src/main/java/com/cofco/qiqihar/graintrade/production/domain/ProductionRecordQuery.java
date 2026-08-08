package com.cofco.qiqihar.graintrade.production.domain;

import java.util.Map;
import java.util.Set;

public record ProductionRecordQuery(
        String productCode, String pageKind, int pageNumber, int pageSize, Map<String, String> filters,
        Set<String> authorizedRegionCodes) {
    public ProductionRecordQuery {
        filters = Map.copyOf(filters);
        authorizedRegionCodes = Set.copyOf(authorizedRegionCodes);
    }

    public ProductionRecordQuery(
            String productCode, String pageKind, int pageNumber, int pageSize, Map<String, String> filters) {
        this(productCode, pageKind, pageNumber, pageSize, filters, Set.of("*"));
    }

    public ProductionRecordQuery authorizedFor(Set<String> regionCodes) {
        return new ProductionRecordQuery(productCode, pageKind, pageNumber, pageSize, filters, regionCodes);
    }
}
