package com.cofco.qiqihar.graintrade.formalsampleobservation.application;

import tools.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record FormalSampleObservationHistoryItem(
        UUID observationId,
        OffsetDateTime observedAt,
        OffsetDateTime officialSavedAt,
        String actorDisplayName,
        String projectionVersion,
        List<String> synchronizedModules,
        JsonNode values,
        boolean latest) {
    public FormalSampleObservationHistoryItem {
        synchronizedModules = List.copyOf(synchronizedModules);
    }
}
