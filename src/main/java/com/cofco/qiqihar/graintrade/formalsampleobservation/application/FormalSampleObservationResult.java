package com.cofco.qiqihar.graintrade.formalsampleobservation.application;

import tools.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record FormalSampleObservationResult(
        UUID observationId,
        UUID samplePointId,
        FormalSampleObservationDomain domain,
        String productCode,
        OffsetDateTime observedAt,
        OffsetDateTime officialSavedAt,
        String projectionVersion,
        List<String> synchronizedModules,
        JsonNode values) {
    public FormalSampleObservationResult {
        synchronizedModules = List.copyOf(synchronizedModules);
    }
}
