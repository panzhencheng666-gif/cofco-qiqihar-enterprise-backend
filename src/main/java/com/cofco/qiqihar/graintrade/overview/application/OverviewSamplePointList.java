package com.cofco.qiqihar.graintrade.overview.application;

import java.util.List;
import java.util.UUID;

public record OverviewSamplePointList(
        String regionCode,
        long totalCount,
        long validCoordinateCount,
        long dataQualityIssueCount,
        long correctionSourceCount,
        long unresolvedSourceCount,
        List<Category> categories,
        List<Item> items,
        List<CorrectionSource> correctionSources) {
    public OverviewSamplePointList {
        categories = List.copyOf(categories);
        items = List.copyOf(items);
        correctionSources = List.copyOf(correctionSources);
    }

    public record Category(String code, String name, long count, List<Type> types) {
        public Category { types = List.copyOf(types); }
    }

    public record Type(String code, String name, String iconKey, long count) {}
    public record CategoryRef(String code, String name) {}
    public record TypeRef(String code, String name, String iconKey) {}
    public record ProductRef(String code, String name) {}

    public record Item(
            UUID samplePointId,
            String name,
            String regionCode,
            String regionName,
            String locationState,
            String dataQualityReason,
            List<CategoryRef> categories,
            List<TypeRef> types,
            List<ProductRef> products) {
        public Item {
            categories = List.copyOf(categories);
            types = List.copyOf(types);
            products = List.copyOf(products);
        }
    }

    public record CorrectionSource(
            String categoryCode,
            String sourceRecordId,
            String sourceRole,
            String dataQualityReason) {}
}
