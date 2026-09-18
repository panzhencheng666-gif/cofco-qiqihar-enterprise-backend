package com.cofco.qiqihar.graintrade.overview.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record OperationalSituationCatalogue(
        Instant generatedAt,
        List<WeatherObservation> weather,
        List<PublicEvent> publicEvents,
        List<SourceStatus> sources) {

    public record WeatherObservation(
            String rootRegionCode,
            String regionName,
            BigDecimal longitude,
            BigDecimal latitude,
            Instant observedAt,
            BigDecimal meanTemperatureC,
            BigDecimal precipitationMm,
            BigDecimal soilMoisturePercent,
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

    public record SourceStatus(
            String code,
            String label,
            String status,
            Instant lastAttemptAt,
            Instant lastSuccessAt,
            String sourceUrl,
            String notice) {}
}
