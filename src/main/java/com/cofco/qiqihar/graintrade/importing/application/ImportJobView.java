package com.cofco.qiqihar.graintrade.importing.application;

import com.cofco.qiqihar.graintrade.importing.domain.ImportJob;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record ImportJobView(UUID id, UUID actionJobId, String domainCode, String statusCode,
        int importedRows, int failedRows,
        int warningRows, List<String> productCodes, List<String> surveyPeriods,
        UUID retryOf, Instant createdAt, Instant startedAt, Instant completedAt, int attemptCount,
        String failureCode, String failureMessage) {
    public static ImportJobView from(ImportJob job) {
        return new ImportJobView(job.id(), job.id(), job.domainCode(), job.statusCode(),
                job.importedRows(), job.failedRows(),
                job.warningRows(), values(job, "productCode"), periods(job),
                job.retryOf(), job.createdAt(), job.startedAt(), job.completedAt(), job.attemptCount(),
                job.failureCode(), job.failureMessage());
    }

    private static List<String> values(ImportJob job, String key) {
        return job.rows().stream().map(row -> normalized(row.values().get(key)))
                .filter(Objects::nonNull).distinct().sorted().toList();
    }

    private static List<String> periods(ImportJob job) {
        return job.rows().stream().map(row -> {
            String year = normalized(row.values().get("surveyYear"));
            String month = normalized(row.values().get("surveyMonth"));
            if (year == null) return null;
            if (month == null) return year;
            if (month.matches("[0-9]{1,2}")) return year + "-" + String.format("%02d", Integer.parseInt(month));
            return year + "-" + month;
        }).filter(Objects::nonNull).distinct().sorted().toList();
    }

    private static String normalized(String value) {
        if (value == null || value.isBlank()) return null;
        return value.strip();
    }
}
