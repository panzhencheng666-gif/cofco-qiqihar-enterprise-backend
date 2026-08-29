package com.cofco.qiqihar.graintrade.formalsampleobservation.application;

import tools.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record EligibleFormalSample(
        UUID samplePointId,
        String sampleName,
        String objectTypeCode,
        String objectTypeName,
        FormalSampleObservationDomain domain,
        String productCode,
        String regionCode,
        String regionName,
        String latitude,
        String longitude,
        long coordinateVersion,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        String latestObservationId,
        OffsetDateTime latestObservedAt,
        JsonNode latestValues) {
}
