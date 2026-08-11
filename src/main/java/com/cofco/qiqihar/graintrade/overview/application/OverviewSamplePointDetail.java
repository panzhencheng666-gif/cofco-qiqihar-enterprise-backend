package com.cofco.qiqihar.graintrade.overview.application;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record OverviewSamplePointDetail(
        UUID samplePointId,
        String name,
        String regionCode,
        String regionName,
        String locationState,
        List<Association> associations) {
    public OverviewSamplePointDetail { associations = List.copyOf(associations); }

    public record Association(
            String categoryCode,
            String categoryName,
            String sourceRole,
            String typeCode,
            String typeName,
            String productCode,
            String productName,
            LocalDate occurrenceDate,
            long sourceVersion,
            Map<String, BusinessValue> businessValues) {
        public Association { businessValues = Map.copyOf(businessValues); }
    }

    public record BusinessValue(String label, String value, String unitCode) {}
}
