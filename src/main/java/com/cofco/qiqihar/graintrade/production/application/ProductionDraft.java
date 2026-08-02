package com.cofco.qiqihar.graintrade.production.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

public record ProductionDraft(
        String productCode, String objectTypeCode, String regionCode, String cultivarCode,
        LocalDate surveyDate, BigDecimal cultivatedAreaMu,
        BigDecimal yieldPerMuKilograms, Map<String, BigDecimal> quality,
        Map<String, BigDecimal> costs, Map<String, BigDecimal> insurance, Map<String, BigDecimal> subsidies) {
    public ProductionDraft {
        quality = copy(quality);
        costs = copy(costs);
        insurance = copy(insurance);
        subsidies = copy(subsidies);
    }

    private static Map<String, BigDecimal> copy(Map<String, BigDecimal> values) {
        return values == null ? Map.of() : Map.copyOf(values);
    }
}
