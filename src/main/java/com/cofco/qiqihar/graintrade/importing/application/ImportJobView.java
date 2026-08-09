package com.cofco.qiqihar.graintrade.importing.application;

import com.cofco.qiqihar.graintrade.importing.domain.ImportJob;
import java.time.Instant;
import java.util.UUID;

public record ImportJobView(UUID id, String domainCode, String statusCode, int importedRows, int failedRows,
        UUID retryOf, Instant createdAt, Instant startedAt, Instant completedAt, int attemptCount,
        String failureCode, String failureMessage) {
    public static ImportJobView from(ImportJob job) {
        return new ImportJobView(job.id(), job.domainCode(), job.statusCode(), job.importedRows(), job.failedRows(),
                job.retryOf(), job.createdAt(), job.startedAt(), job.completedAt(), job.attemptCount(),
                job.failureCode(), job.failureMessage());
    }
}
