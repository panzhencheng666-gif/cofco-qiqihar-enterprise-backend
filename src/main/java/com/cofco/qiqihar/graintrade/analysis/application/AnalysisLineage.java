package com.cofco.qiqihar.graintrade.analysis.application;

import java.time.OffsetDateTime;
import java.util.List;

public record AnalysisLineage(
        String sourceDomain,
        String recordId,
        long recordVersion,
        List<String> factCodes,
        String subjectLabel,
        String regionLabel,
        String periodLabel,
        OffsetDateTime approvedAt) {

    public AnalysisLineage {
        if (blank(sourceDomain) || blank(recordId) || recordVersion < 0
                || factCodes == null || factCodes.isEmpty()
                || blank(subjectLabel) || blank(regionLabel) || blank(periodLabel)
                || approvedAt == null) {
            throw new IllegalArgumentException("Analysis lineage is invalid");
        }
        factCodes = factCodes.stream().sorted().distinct().toList();
    }

    String canonicalKey() {
        return String.join(":", sourceDomain, recordId, Long.toString(recordVersion),
                String.join(",", factCodes));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
