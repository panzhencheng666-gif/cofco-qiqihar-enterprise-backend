package com.cofco.qiqihar.graintrade.overview.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record OperationalFacilityCatalogue(
        String regionCode,
        String productCode,
        LocalDate asOf,
        List<Category> storageCategories,
        List<StorageFacility> storageFacilities,
        List<RailwayFacility> railwayFacilities,
        List<RailwayLine> railwayLines,
        List<SourceStatus> sources) {

    public record Category(String code, String label, long count) {}

    public record StorageFacility(
            String code, String name, String workUnitCode, String relationType, String relationLabel,
            String regionCode, String regionName, String address, BigDecimal longitude, BigDecimal latitude,
            String coordinatePrecision, String coordinatePrecisionLabel, String operationalStatus,
            BigDecimal capacityTonnes, LocalDate capacityAsOf, List<Price> prices, List<Evidence> evidence) {}

    public record Price(
            String productCode, String productName, String qualityRequirement, BigDecimal value, String unit,
            LocalDate effectiveOn, LocalDate expiresOn, String sourceName, String sourceUrl,
            String sourceClassification, boolean current) {}

    public record Evidence(
            String kind, String title, String sourceName, String sourceUrl, String sourceClassification,
            LocalDate sourceAsOf, String note) {}

    public record RailwayFacility(
            String sourceId, String name, String kind, BigDecimal longitude, BigDecimal latitude,
            String operator, String reference, String status, String service, String locationRelation,
            BigDecimal distanceKm, String nearbyLines, String sourceUrl) {}

    public record RailwayLine(
            String name, BigDecimal mappedTrackKm, String usage, String electrification,
            String gauge, String operator, String sourceUrl) {}

    public record SourceStatus(
            String code, String label, String status, String sourceAsOf, String sourceUrl, String notice) {}
}
