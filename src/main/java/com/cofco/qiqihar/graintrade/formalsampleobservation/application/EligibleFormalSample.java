package com.cofco.qiqihar.graintrade.formalsampleobservation.application;

import tools.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record EligibleFormalSample(
        UUID samplePointId,
        String sampleName,
        String address,
        String objectTypeCode,
        String objectTypeName,
        FormalSampleObservationDomain domain,
        String productCode,
        String regionCode,
        String regionName,
        String maintainerSubjectId,
        String maintainerDisplayName,
        String latitude,
        String longitude,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        long version,
        long annualObservationCount,
        long networkMembershipCount,
        String latestObservationId,
        OffsetDateTime latestObservedAt,
        JsonNode latestValues) {
}
