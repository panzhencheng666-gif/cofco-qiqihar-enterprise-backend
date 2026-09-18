package com.cofco.qiqihar.graintrade.overview.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public interface OperationalSituationRepository {
    Snapshot snapshot();

    void replaceEvents(String sourceCode, List<FeedEvent> events, Instant attemptedAt);

    void recordFailure(String sourceCode, Instant attemptedAt, String message);

    record FeedEvent(
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
            String evidenceUrl) {}

    record RefreshState(
            String sourceCode,
            String sourceName,
            String sourceUrl,
            Instant lastAttemptAt,
            Instant lastSuccessAt,
            String lastError,
            int recordCount) {}

    record Snapshot(
            List<OperationalSituationCatalogue.WeatherObservation> weather,
            List<OperationalSituationCatalogue.PublicEvent> events,
            List<RefreshState> refreshStates) {}
}
