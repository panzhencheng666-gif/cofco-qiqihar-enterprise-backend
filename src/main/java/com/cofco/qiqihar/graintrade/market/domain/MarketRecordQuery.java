package com.cofco.qiqihar.graintrade.market.domain;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record MarketRecordQuery(
        String productCode,
        String pageKind,
        int pageNumber,
        int pageSize,
        Map<String, String> filters,
        Set<String> authorizedRegionCodes) {

    public MarketRecordQuery {
        productCode = requireText(productCode, "productCode");
        pageKind = requireText(pageKind, "pageKind");
        if (pageNumber < 0) {
            throw new IllegalArgumentException("pageNumber must not be negative");
        }
        if (pageSize < 1) {
            throw new IllegalArgumentException("pageSize must be positive");
        }
        filters = Map.copyOf(filters);
        authorizedRegionCodes = Set.copyOf(authorizedRegionCodes);
    }

    public MarketRecordQuery(String productCode, String pageKind, int pageNumber, int pageSize,
            Map<String, String> filters) {
        this(productCode, pageKind, pageNumber, pageSize, filters, Set.of("*"));
    }

    public MarketRecordQuery authorizedFor(Set<String> regionCodes) {
        return new MarketRecordQuery(productCode, pageKind, pageNumber, pageSize, filters, regionCodes);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
