package com.cofco.qiqihar.graintrade.importing.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ImportJob(
        UUID id,
        String domainCode,
        String idempotencyKey,
        String contentSha256,
        String requestedBy,
        String workUnitCode,
        UUID retryOf,
        String statusCode,
        Instant createdAt,
        Instant completedAt,
        List<ImportRowOutcome> rows) {
    public ImportJob { rows = List.copyOf(rows); }

    public int importedRows() { return (int) rows.stream().filter(row -> row.outcomeCode().equals("IMPORTED")).count(); }
    public int failedRows() { return (int) rows.stream().filter(row -> row.outcomeCode().equals("ERROR")).count(); }
}
