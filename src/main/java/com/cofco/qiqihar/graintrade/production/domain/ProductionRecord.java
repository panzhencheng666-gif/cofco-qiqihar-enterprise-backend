package com.cofco.qiqihar.graintrade.production.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** A production aggregate. Every normalized fact category is rehydrated before any transition. */
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
        Map<String, BigDecimal> quality,
        Map<String, BigDecimal> costs,
        Map<String, BigDecimal> insurance,
        Map<String, BigDecimal> subsidies,
        long version) {

    private static final int INPUT_PRECISION = 18;
    private static final int INPUT_SCALE = 4;
    private static final BigDecimal MAX_INPUT = new BigDecimal("99999999999999.9999");
    private static final BigDecimal MAX_OUTPUT = new BigDecimal("999999999999999999.9999");

    public ProductionRecord {
        requireText(id, "id");
        requireText(productCode, "product code");
        requireText(objectTypeCode, "object type");
        requireText(regionCode, "region code");
        Objects.requireNonNull(surveyDate, "survey date must not be null");
        Objects.requireNonNull(reportedAt, "reported at must not be null");
        Objects.requireNonNull(status, "status must not be null");
        if (surveyDate.isAfter(reportedAt.toLocalDate())) {
            throw new IllegalArgumentException("survey date cannot be after reported at date");
        }
        cultivatedAreaMu = input(cultivatedAreaMu, "cultivated area");
        yieldPerMuKilograms = input(yieldPerMuKilograms, "yield per mu");
        BigDecimal calculated = cultivatedAreaMu.multiply(yieldPerMuKilograms)
                .setScale(INPUT_SCALE, RoundingMode.HALF_UP);
        if (calculated.compareTo(MAX_OUTPUT) > 0) {
            throw new IllegalArgumentException("estimated output exceeds database precision");
        }
        estimatedOutputKilograms = decimal(estimatedOutputKilograms, MAX_OUTPUT, "estimated output");
        if (calculated.compareTo(estimatedOutputKilograms) != 0) {
            throw new IllegalArgumentException("estimated output must equal normalized area multiplied by normalized yield");
        }
        quality = facts(quality, "quality");
        costs = facts(costs, "cost");
        insurance = facts(insurance, "insurance");
        subsidies = facts(subsidies, "subsidy");
        if (version < 0) throw new IllegalArgumentException("version must not be negative");
    }

    public static ProductionRecord draft(
            String id, String productCode, String objectTypeCode, String regionCode, String cultivarCode,
            LocalDate surveyDate, OffsetDateTime reportedAt, BigDecimal cultivatedAreaMu,
            BigDecimal yieldPerMuKilograms, Map<String, BigDecimal> quality) {
        return draft(id, productCode, objectTypeCode, regionCode, cultivarCode, surveyDate, reportedAt,
                cultivatedAreaMu, yieldPerMuKilograms, quality, Map.of(), Map.of(), Map.of());
    }

    public static ProductionRecord draft(
            String id, String productCode, String objectTypeCode, String regionCode, String cultivarCode,
            LocalDate surveyDate, OffsetDateTime reportedAt, BigDecimal cultivatedAreaMu,
            BigDecimal yieldPerMuKilograms, Map<String, BigDecimal> quality, Map<String, BigDecimal> costs,
            Map<String, BigDecimal> insurance, Map<String, BigDecimal> subsidies) {
        BigDecimal area = input(cultivatedAreaMu, "cultivated area");
        BigDecimal yield = input(yieldPerMuKilograms, "yield per mu");
        return new ProductionRecord(id, productCode, objectTypeCode, regionCode, cultivarCode, surveyDate,
                reportedAt, area, yield, area.multiply(yield).setScale(INPUT_SCALE, RoundingMode.HALF_UP),
                ProductionStatus.DRAFT, null, quality, costs, insurance, subsidies, 0);
    }

    public ProductionRecord revise(
            String nextProductCode, String nextObjectTypeCode, String nextRegionCode, String nextCultivarCode,
            LocalDate nextSurveyDate, OffsetDateTime nextReportedAt, BigDecimal nextArea, BigDecimal nextYield,
            Map<String, BigDecimal> nextQuality, Map<String, BigDecimal> nextCosts,
            Map<String, BigDecimal> nextInsurance, Map<String, BigDecimal> nextSubsidies) {
        if (status != ProductionStatus.DRAFT && status != ProductionStatus.RETURNED) {
            throw new IllegalStateException("Only DRAFT or RETURNED records may be revised");
        }
        BigDecimal area = input(nextArea, "cultivated area");
        BigDecimal yield = input(nextYield, "yield per mu");
        return new ProductionRecord(id, nextProductCode, nextObjectTypeCode, nextRegionCode, nextCultivarCode,
                nextSurveyDate, nextReportedAt, area, yield,
                area.multiply(yield).setScale(INPUT_SCALE, RoundingMode.HALF_UP), ProductionStatus.DRAFT, null,
                nextQuality, nextCosts, nextInsurance, nextSubsidies, version);
    }

    public ProductionRecord submit() {
        if (status != ProductionStatus.DRAFT && status != ProductionStatus.RETURNED) {
            throw new IllegalStateException("Cannot transition production record from " + status + " to PENDING_REVIEW");
        }
        return copy(ProductionStatus.PENDING_REVIEW, null, version);
    }

    public ProductionRecord approve() {
        return transition(ProductionStatus.PENDING_REVIEW, ProductionStatus.APPROVED, null);
    }

    public ProductionRecord returnForCorrection(String reason) {
        requireText(reason, "return reason");
        return transition(ProductionStatus.PENDING_REVIEW, ProductionStatus.RETURNED, reason);
    }

    public ProductionRecord savedAsVersion(long savedVersion) {
        return copy(status, returnReason, savedVersion);
    }

    private ProductionRecord transition(ProductionStatus expected, ProductionStatus next, String reason) {
        if (status != expected) {
            throw new IllegalStateException("Cannot transition production record from " + status + " to " + next);
        }
        return copy(next, reason, version);
    }

    private ProductionRecord copy(ProductionStatus next, String reason, long nextVersion) {
        return new ProductionRecord(id, productCode, objectTypeCode, regionCode, cultivarCode, surveyDate,
                reportedAt, cultivatedAreaMu, yieldPerMuKilograms, estimatedOutputKilograms, next, reason,
                quality, costs, insurance, subsidies, nextVersion);
    }

    private static BigDecimal input(BigDecimal value, String description) {
        return decimal(value, MAX_INPUT, description);
    }

    private static BigDecimal decimal(BigDecimal value, BigDecimal maximum, String description) {
        Objects.requireNonNull(value, description + " must not be null");
        BigDecimal normalized = value.setScale(INPUT_SCALE, RoundingMode.HALF_UP);
        if (normalized.signum() < 0) throw new IllegalArgumentException(description + " must not be negative");
        if (normalized.compareTo(maximum) > 0 || normalized.precision() > INPUT_PRECISION + 4) {
            throw new IllegalArgumentException(description + " exceeds database precision");
        }
        return normalized;
    }

    private static Map<String, BigDecimal> facts(Map<String, BigDecimal> values, String category) {
        Map<String, BigDecimal> normalized = new LinkedHashMap<>();
        Objects.requireNonNull(values, category + " facts must not be null").forEach((code, value) -> {
            requireText(code, category + " fact code");
            normalized.put(code, input(value, category + " fact value"));
        });
        return Map.copyOf(normalized);
    }

    private static void requireText(String value, String description) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(description + " must not be blank");
    }
}
