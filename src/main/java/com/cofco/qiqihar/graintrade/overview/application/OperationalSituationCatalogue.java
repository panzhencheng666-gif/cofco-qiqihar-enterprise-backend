package com.cofco.qiqihar.graintrade.overview.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record OperationalSituationCatalogue(
        Instant generatedAt,
        List<WeatherObservation> weather,
        List<PublicEvent> publicEvents,
        List<PolicyEvent> policyEvents,
        List<LogisticsFlow> logisticsFlows,
        List<InventorySnapshot> inventories,
        List<SourceStatus> sources) {

    public record WeatherObservation(
            String rootRegionCode,
            String regionCode,
            String regionName,
            BigDecimal longitude,
            BigDecimal latitude,
            Instant observedAt,
            BigDecimal meanTemperatureC,
            BigDecimal precipitationMm,
            BigDecimal soilMoisturePercent,
            Integer weatherCode,
            BigDecimal windSpeedKph,
            BigDecimal windDirectionDegrees,
            BigDecimal cloudCoverPercent,
            String observationPrecision,
            String risk,
            String assessment,
            String sourceName,
            String sourceUrl,
            Instant fetchedAt) {}

    public record PublicEvent(
            String eventId,
            String title,
            String description,
            String categoryCode,
            String categoryLabel,
            BigDecimal longitude,
            BigDecimal latitude,
            Instant observedAt,
            BigDecimal magnitudeValue,
            String magnitudeUnit,
            String eventUrl,
            String evidenceUrl,
            Instant fetchedAt) {}

    public record PolicyEvent(
            String sourceId,
            String rootRegionCode,
            String title,
            String summary,
            LocalDate publishedOn,
            String sourceName,
            String sourceUrl,
            Instant verifiedAt) {}

    public record LogisticsFlow(
            String eventId,
            String productCode,
            String direction,
            String originRegionCode,
            String originRegionName,
            BigDecimal originLongitude,
            BigDecimal originLatitude,
            String destinationRegionCode,
            String destinationRegionName,
            BigDecimal destinationLongitude,
            BigDecimal destinationLatitude,
            BigDecimal volumeTonnes,
            Instant occurredAt,
            String transportMode) {}

    public record InventorySnapshot(
            String regionCode,
            String regionName,
            String productCode,
            BigDecimal longitude,
            BigDecimal latitude,
            BigDecimal inventoryTonnes,
            int sourceCount,
            Instant observedAt) {}

    public record SourceStatus(
            String code,
            String label,
            String status,
            Instant lastAttemptAt,
            Instant lastSuccessAt,
            String sourceUrl,
            String notice) {}
}
