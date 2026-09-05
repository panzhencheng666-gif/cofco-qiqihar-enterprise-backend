package com.cofco.qiqihar.graintrade.formalsampleobservation.application;

import tools.jackson.databind.JsonNode;
import com.cofco.qiqihar.graintrade.formalsamplepoint.FormalSampleLocationDraft;
import java.time.OffsetDateTime;
import java.util.UUID;

public record FormalSampleObservationCommand(
        FormalSampleObservationDomain domain,
        UUID samplePointId,
        String productCode,
        OffsetDateTime observedAt,
        JsonNode payload,
        FormalSampleLocationDraft sampleLocation) {
    public FormalSampleObservationCommand(FormalSampleObservationDomain domain, UUID samplePointId,
            String productCode, OffsetDateTime observedAt, JsonNode payload) {
        this(domain, samplePointId, productCode, observedAt, payload, null);
    }
}
