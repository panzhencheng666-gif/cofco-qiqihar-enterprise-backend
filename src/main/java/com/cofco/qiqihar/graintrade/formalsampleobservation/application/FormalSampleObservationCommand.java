package com.cofco.qiqihar.graintrade.formalsampleobservation.application;

import tools.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.UUID;

public record FormalSampleObservationCommand(
        FormalSampleObservationDomain domain,
        UUID samplePointId,
        String productCode,
        OffsetDateTime observedAt,
        JsonNode payload) {
}
