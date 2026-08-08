package com.cofco.qiqihar.graintrade.production.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

public record ProductionDraft(
        String productCode, String objectTypeCode, String regionCode, String cultivarCode,
        LocalDate surveyDate, BigDecimal cultivatedAreaMu,
        BigDecimal yieldPerMuKilograms, Map<String, BigDecimal> quality,
        Map<String, BigDecimal> costs, Map<String, BigDecimal> insurance, Map<String, BigDecimal> subsidies,
        Map<String, String> submissionMetadata) {

    public Map<String, String> submissionMetadata() {
        return submissionMetadata;
    }
    public ProductionDraft(
            String productCode, String objectTypeCode, String regionCode, String cultivarCode,
            LocalDate surveyDate, BigDecimal cultivatedAreaMu,
            BigDecimal yieldPerMuKilograms, Map<String, BigDecimal> quality,
            Map<String, BigDecimal> costs, Map<String, BigDecimal> insurance, Map<String, BigDecimal> subsidies) {
        this(productCode, objectTypeCode, regionCode, cultivarCode, surveyDate, cultivatedAreaMu,
                yieldPerMuKilograms, quality, costs, insurance, subsidies, Map.of());
    }

    public ProductionDraft {
        quality = copy(quality);
        costs = copy(costs);
        insurance = copy(insurance);
        subsidies = copy(subsidies);
        Map<String, String> metadata = new LinkedHashMap<>();
        if (submissionMetadata != null) submissionMetadata.forEach(metadata::put);
        submissionMetadata = Map.copyOf(metadata);
    }

    private static Map<String, BigDecimal> copy(Map<String, BigDecimal> values) {
        return values == null ? Map.of() : Map.copyOf(values);
    }
}
