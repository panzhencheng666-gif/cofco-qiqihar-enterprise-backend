package com.cofco.qiqihar.graintrade.production.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Objects;

/** A normalized production report; quality, costs, insurance and subsidies are persisted separately. */
public record ProductionRecord(
        String id,
        String productCode,
        String objectTypeCode,
        String regionCode,
        String cultivarCode,
        LocalDate surveyDate,
        OffsetDateTime reportedAt,
        BigDecimal cultivatedAreaMu,
        BigDecimal yieldPerMuKilograms,
        BigDecimal estimatedOutputKilograms,
        ProductionStatus status,
        String returnReason,
        Map<String, BigDecimal> quality) {

    public ProductionRecord {
        requireText(id, "id");
        requireText(productCode, "product code");
        requireText(objectTypeCode, "object type");
        requireText(regionCode, "region code");
        Objects.requireNonNull(surveyDate, "survey date must not be null");
        Objects.requireNonNull(reportedAt, "reported at must not be null");
        Objects.requireNonNull(cultivatedAreaMu, "cultivated area must not be null");
        Objects.requireNonNull(yieldPerMuKilograms, "yield per mu must not be null");
        Objects.requireNonNull(estimatedOutputKilograms, "estimated output must not be null");
        Objects.requireNonNull(status, "status must not be null");
        if (surveyDate.isAfter(reportedAt.toLocalDate())) {
            throw new IllegalArgumentException("survey date cannot be after reported at date");
        }
        if (cultivatedAreaMu.signum() < 0 || yieldPerMuKilograms.signum() < 0) {
            throw new IllegalArgumentException("area and yield must not be negative");
        }
        BigDecimal computed = cultivatedAreaMu.multiply(yieldPerMuKilograms).setScale(4, RoundingMode.HALF_UP);
        if (computed.compareTo(estimatedOutputKilograms) != 0) {
            throw new IllegalArgumentException("estimated output must equal area multiplied by yield");
        }
        quality = Map.copyOf(Objects.requireNonNull(quality, "quality must not be null"));
        quality.forEach((code, value) -> {
            requireText(code, "quality code");
            if (value == null || value.signum() < 0) {
                throw new IllegalArgumentException("quality value must not be negative");
            }
        });
    }

    public static ProductionRecord draft(
            String id, String productCode, String objectTypeCode, String regionCode, String cultivarCode,
            LocalDate surveyDate, OffsetDateTime reportedAt, BigDecimal cultivatedAreaMu,
            BigDecimal yieldPerMuKilograms, Map<String, BigDecimal> quality) {
        return new ProductionRecord(id, productCode, objectTypeCode, regionCode, cultivarCode, surveyDate,
                reportedAt, cultivatedAreaMu, yieldPerMuKilograms,
                cultivatedAreaMu.multiply(yieldPerMuKilograms).setScale(4, RoundingMode.HALF_UP),
                ProductionStatus.DRAFT, null, quality);
    }

    public ProductionRecord submit() {
        return transition(ProductionStatus.DRAFT, ProductionStatus.PENDING_REVIEW, null);
    }

    public ProductionRecord approve() {
        return transition(ProductionStatus.PENDING_REVIEW, ProductionStatus.APPROVED, null);
    }

    public ProductionRecord returnForCorrection(String reason) {
        requireText(reason, "return reason");
        return transition(ProductionStatus.PENDING_REVIEW, ProductionStatus.RETURNED, reason);
    }

    public ProductionRecord saveDraft() {
        if (status != ProductionStatus.DRAFT && status != ProductionStatus.RETURNED) {
            throw new IllegalStateException("Only DRAFT or RETURNED records may be saved as a draft");
        }
        return new ProductionRecord(id, productCode, objectTypeCode, regionCode, cultivarCode, surveyDate,
                reportedAt, cultivatedAreaMu, yieldPerMuKilograms, estimatedOutputKilograms,
                ProductionStatus.DRAFT, null, quality);
    }

    private ProductionRecord transition(ProductionStatus expected, ProductionStatus next, String reason) {
        if (status != expected) {
            throw new IllegalStateException("Cannot transition production record from " + status + " to " + next);
        }
        return new ProductionRecord(id, productCode, objectTypeCode, regionCode, cultivarCode, surveyDate,
                reportedAt, cultivatedAreaMu, yieldPerMuKilograms, estimatedOutputKilograms, next, reason, quality);
    }

    private static void requireText(String value, String description) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(description + " must not be blank");
        }
    }
}
