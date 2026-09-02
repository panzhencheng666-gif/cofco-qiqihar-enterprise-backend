package com.cofco.qiqihar.graintrade.importing.application;

import java.time.Instant;
import java.util.UUID;

public record SamplePointImportResult(
        UUID id,
        String statusCode,
        int importedRows,
        int failedRows,
        Instant completedAt,
        boolean replayed) {}
