package com.cofco.qiqihar.graintrade.production.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ProductionDraft(
        String productCode, String objectTypeCode, String regionCode, String cultivarCode,
        LocalDate surveyDate, BigDecimal cultivatedAreaMu,
        BigDecimal yieldPerMuKilograms, Map<String, BigDecimal> quality,
        Map<String, BigDecimal> costs, Map<String, BigDecimal> insurance, Map<String, BigDecimal> subsidies,
        Map<String, String> submissionMetadata, List<UUID> evidencePhotoIds,
        int surveyYear, Integer surveyMonth) {

    public Map<String, String> submissionMetadata() {
        return submissionMetadata;
    }
    public ProductionDraft(
            String productCode, String objectTypeCode, String regionCode, String cultivarCode,
            LocalDate surveyDate, BigDecimal cultivatedAreaMu,
            BigDecimal yieldPerMuKilograms, Map<String, BigDecimal> quality,
            Map<String, BigDecimal> costs, Map<String, BigDecimal> insurance, Map<String, BigDecimal> subsidies,
            Map<String, String> submissionMetadata, List<UUID> evidencePhotoIds) {
        this(productCode, objectTypeCode, regionCode, cultivarCode, surveyDate, cultivatedAreaMu,
                yieldPerMuKilograms, quality, costs, insurance, subsidies, submissionMetadata, evidencePhotoIds,
                surveyDate == null ? 0 : surveyDate.getYear(), surveyDate == null ? null : surveyDate.getMonthValue());
    }
    public ProductionDraft(
            String productCode, String objectTypeCode, String regionCode, String cultivarCode,
            LocalDate surveyDate, BigDecimal cultivatedAreaMu,
            BigDecimal yieldPerMuKilograms, Map<String, BigDecimal> quality,
            Map<String, BigDecimal> costs, Map<String, BigDecimal> insurance, Map<String, BigDecimal> subsidies,
            Map<String, String> submissionMetadata) {
        this(productCode, objectTypeCode, regionCode, cultivarCode, surveyDate, cultivatedAreaMu,
                yieldPerMuKilograms, quality, costs, insurance, subsidies, submissionMetadata, List.of(),
                surveyDate == null ? 0 : surveyDate.getYear(), surveyDate == null ? null : surveyDate.getMonthValue());
    }
    public ProductionDraft(
            String productCode, String objectTypeCode, String regionCode, String cultivarCode,
            LocalDate surveyDate, BigDecimal cultivatedAreaMu,
            BigDecimal yieldPerMuKilograms, Map<String, BigDecimal> quality,
            Map<String, BigDecimal> costs, Map<String, BigDecimal> insurance, Map<String, BigDecimal> subsidies) {
        this(productCode, objectTypeCode, regionCode, cultivarCode, surveyDate, cultivatedAreaMu,
                yieldPerMuKilograms, quality, costs, insurance, subsidies, Map.of(), List.of(),
                surveyDate == null ? 0 : surveyDate.getYear(), surveyDate == null ? null : surveyDate.getMonthValue());
    }

    public ProductionDraft {
        quality = copy(quality);
        costs = copy(costs);
        insurance = copy(insurance);
        subsidies = copy(subsidies);
        Map<String, String> metadata = new LinkedHashMap<>();
        if (submissionMetadata != null) submissionMetadata.forEach(metadata::put);
        submissionMetadata = Map.copyOf(metadata);
        evidencePhotoIds = evidencePhotoIds == null ? List.of() : List.copyOf(evidencePhotoIds);
        if (surveyYear < 1900 || surveyYear > 2200) {
            throw new IllegalArgumentException("survey year is outside range");
        }
        if (surveyMonth != null && (surveyMonth < 1 || surveyMonth > 12)) {
            throw new IllegalArgumentException("survey month is outside range");
        }
        if (surveyDate == null || surveyDate.getYear() != surveyYear
                || (surveyMonth != null && surveyDate.getMonthValue() != surveyMonth)) {
            throw new IllegalArgumentException("survey date is inconsistent with data time");
        }
    }

    private static Map<String, BigDecimal> copy(Map<String, BigDecimal> values) {
        return values == null ? Map.of() : Map.copyOf(values);
    }
}
