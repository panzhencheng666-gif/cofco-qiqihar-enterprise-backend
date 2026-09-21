package com.cofco.qiqihar.riskintelligence.integration;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record SourceFact(
        String sourceSystem,
        String sourceRecordType,
        String sourceRecordId,
        String sourceVersion,
        Instant businessOccurredAt,
        Map<String, Object> payload) {

    public SourceFact {
        sourceSystem = requireText(sourceSystem, "sourceSystem", 80);
        sourceRecordType = requireText(sourceRecordType, "sourceRecordType", 100);
        sourceRecordId = requireText(sourceRecordId, "sourceRecordId", 200);
        sourceVersion = requireText(sourceVersion, "sourceVersion", 160);
        businessOccurredAt = Objects.requireNonNull(businessOccurredAt, "businessOccurredAt");
        payload = Map.copyOf(Objects.requireNonNull(payload, "payload"));
    }

    SourceFactKey key() {
        return new SourceFactKey(sourceSystem, sourceRecordType, sourceRecordId, sourceVersion);
    }

    private static String requireText(String value, String field, int maximumLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        if (value.length() > maximumLength) {
            throw new IllegalArgumentException(field + " exceeds " + maximumLength + " characters");
        }
        return value;
    }
}
