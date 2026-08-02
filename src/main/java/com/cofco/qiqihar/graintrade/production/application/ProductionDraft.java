package com.cofco.qiqihar.graintrade.production.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;

public record ProductionDraft(
        String productCode, String objectTypeCode, String regionCode, String cultivarCode,
        LocalDate surveyDate, OffsetDateTime reportedAt, BigDecimal cultivatedAreaMu,
        BigDecimal yieldPerMuKilograms, Map<String, BigDecimal> quality,
        Map<String, BigDecimal> costs, Map<String, BigDecimal> insurance, Map<String, BigDecimal> subsidies) {
    public ProductionDraft {
        quality = Map.copyOf(quality);
        costs = Map.copyOf(costs);
        insurance = Map.copyOf(insurance);
        subsidies = Map.copyOf(subsidies);
    }
}
