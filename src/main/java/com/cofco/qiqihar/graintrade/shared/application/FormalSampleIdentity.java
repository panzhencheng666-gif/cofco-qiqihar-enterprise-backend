package com.cofco.qiqihar.graintrade.shared.application;

import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Server-owned identity and immutable attributes of an approved formal sample.
 * Business modules may consume this value but cannot select or alter its scope.
 */
public record FormalSampleIdentity(
        UUID samplePointId,
        String sampleName,
        String productCode,
        String regionCode,
        String maintainerSubjectId,
        String latitude,
        String longitude,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        JsonNode lockedValues) {
}
