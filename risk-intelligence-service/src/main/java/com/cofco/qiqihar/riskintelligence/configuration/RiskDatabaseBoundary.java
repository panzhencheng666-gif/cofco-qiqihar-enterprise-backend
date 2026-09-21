package com.cofco.qiqihar.riskintelligence.configuration;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

public record RiskDatabaseBoundary(
        String databaseName,
        String databaseUser,
        Set<String> writableSchemas,
        Instant verifiedAt) {

    public RiskDatabaseBoundary {
        databaseName = requireText(databaseName, "databaseName");
        databaseUser = requireText(databaseUser, "databaseUser");
        writableSchemas = Set.copyOf(Objects.requireNonNull(writableSchemas, "writableSchemas"));
        verifiedAt = Objects.requireNonNull(verifiedAt, "verifiedAt");
    }

    public static void verify(
            String currentDatabase,
            String currentUser,
            Set<String> writableSchemas,
            String expectedDatabase) {
        String database = requireText(currentDatabase, "currentDatabase");
        requireText(currentUser, "currentUser");
        String expected = requireText(expectedDatabase, "expectedDatabase");
        Set<String> schemas = new TreeSet<>(
                Objects.requireNonNull(writableSchemas, "writableSchemas"));
        if (!expected.equals(database)) {
            throw new IllegalStateException("Unexpected risk database: " + database);
        }
        if (!schemas.equals(Set.of("risk"))) {
            throw new IllegalStateException(
                    "Risk runtime writable schema set must be exactly [risk], received " + schemas);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(field + " is unavailable");
        }
        return value;
    }
}
