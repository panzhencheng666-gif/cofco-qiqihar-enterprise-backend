package com.cofco.qiqihar.graintrade.importing.domain;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A successfully parsed workbook row awaiting promotion into its domain record. */
public record ImportDraft(
        UUID id,
        String domainCode,
        String productCode,
        String objectTypeCode,
        String sampleName,
        String regionCode,
        String surveyPeriod,
        Map<String, String> values,
        List<String> missingFields,
        int completenessPercent,
        String stateCode,
        String createdBy,
        UUID importJobId,
        int sourceRowNumber,
        int version,
        Instant createdAt,
        Instant updatedAt) {
    public ImportDraft {
        if (id == null || importJobId == null || createdAt == null || updatedAt == null
                || blank(domainCode) || blank(productCode) || blank(sampleName) || blank(regionCode)
                || blank(stateCode) || blank(createdBy) || sourceRowNumber <= 1
                || completenessPercent < 0 || completenessPercent > 100 || version < 0) {
            throw new IllegalArgumentException("INVALID_IMPORT_DRAFT");
        }
        objectTypeCode = blank(objectTypeCode) ? null : objectTypeCode.trim();
        surveyPeriod = blank(surveyPeriod) ? null : surveyPeriod.trim();
        values = Map.copyOf(new LinkedHashMap<>(values == null ? Map.of() : values));
        missingFields = List.copyOf(missingFields == null ? List.of() : missingFields);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
