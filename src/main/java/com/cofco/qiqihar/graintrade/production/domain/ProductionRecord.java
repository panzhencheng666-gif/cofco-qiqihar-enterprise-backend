package com.cofco.qiqihar.graintrade.production.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

/** A production aggregate. Every normalized fact category is rehydrated before any transition. */
public record ProductionRecord(
        String id,
        String productCode,
        String objectTypeCode,
        String regionCode,
        String cultivarCode,
        LocalDate surveyDate,
        int surveyYear,
        Integer surveyMonth,
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
        Map<String, String> submissionMetadata,
        long version) {

    private static final int INPUT_PRECISION = 18;
    private static final int INPUT_SCALE = 4;
    private static final BigDecimal MAX_INPUT = new BigDecimal("99999999999999.9999");
    private static final BigDecimal MAX_OUTPUT = new BigDecimal("999999999999999999.9999");
    private static final ZoneId REPORTING_ZONE = ZoneId.of("Asia/Shanghai");

    public ProductionRecord {
        requireText(id, "id");
        requireText(productCode, "product code");
        requireText(objectTypeCode, "object type");
        requireText(regionCode, "region code");
        if (surveyDate == null) throw invalid("survey date must not be null");
        if (surveyYear < 1900 || surveyYear > 2200) throw invalid("survey year is outside range");
        if (surveyMonth != null && (surveyMonth < 1 || surveyMonth > 12)) {
            throw invalid("survey month is outside range");
        }
        if (surveyDate.getYear() != surveyYear
                || (surveyMonth != null && surveyDate.getMonthValue() != surveyMonth)) {
            throw invalid("survey date is inconsistent with data time");
        }
        if (reportedAt == null) throw invalid("reported at must not be null");
        if (status == null) throw invalid("status must not be null");
        if (surveyDate.isAfter(reportedAt.atZoneSameInstant(REPORTING_ZONE).toLocalDate())) {
            throw invalid("survey date cannot be after reported at date");
        }
        cultivatedAreaMu = input(cultivatedAreaMu, "cultivated area");
        yieldPerMuKilograms = input(yieldPerMuKilograms, "yield per mu");
        BigDecimal calculated = cultivatedAreaMu.multiply(yieldPerMuKilograms)
                .setScale(INPUT_SCALE, RoundingMode.HALF_UP);
        if (calculated.compareTo(MAX_OUTPUT) > 0) {
            throw invalid("estimated output exceeds database precision");
        }
        estimatedOutputKilograms = decimal(estimatedOutputKilograms, MAX_OUTPUT, "estimated output");
        if (calculated.compareTo(estimatedOutputKilograms) != 0) {
            throw invalid("estimated output must equal normalized area multiplied by normalized yield");
        }
        quality = facts(quality, "quality");
        costs = facts(costs, "cost");
        insurance = facts(insurance, "insurance");
        subsidies = facts(subsidies, "subsidy");
        submissionMetadata = Map.copyOf(submissionMetadata == null ? Map.of() : submissionMetadata);
        if (version < 0) throw invalid("version must not be negative");
    }

    public static ProductionRecord draft(
            String id, String productCode, String objectTypeCode, String regionCode, String cultivarCode,
            LocalDate surveyDate, OffsetDateTime reportedAt, BigDecimal cultivatedAreaMu,
            BigDecimal yieldPerMuKilograms, Map<String, BigDecimal> quality) {
        return draft(id, productCode, objectTypeCode, regionCode, cultivarCode, surveyDate, reportedAt,
                cultivatedAreaMu, yieldPerMuKilograms, quality, Map.of(), Map.of(), Map.of(), Map.of());
    }

    public static ProductionRecord draft(
            String id, String productCode, String objectTypeCode, String regionCode, String cultivarCode,
            LocalDate surveyDate, OffsetDateTime reportedAt, BigDecimal cultivatedAreaMu,
            BigDecimal yieldPerMuKilograms, Map<String, BigDecimal> quality, Map<String, BigDecimal> costs,
            Map<String, BigDecimal> insurance, Map<String, BigDecimal> subsidies) {
        return draft(id, productCode, objectTypeCode, regionCode, cultivarCode, surveyDate, reportedAt,
                cultivatedAreaMu, yieldPerMuKilograms, quality, costs, insurance, subsidies, Map.of());
    }

    public static ProductionRecord draft(
            String id, String productCode, String objectTypeCode, String regionCode, String cultivarCode,
            LocalDate surveyDate, OffsetDateTime reportedAt, BigDecimal cultivatedAreaMu,
            BigDecimal yieldPerMuKilograms, Map<String, BigDecimal> quality, Map<String, BigDecimal> costs,
            Map<String, BigDecimal> insurance, Map<String, BigDecimal> subsidies,
            Map<String, String> submissionMetadata) {
        return draft(id, productCode, objectTypeCode, regionCode, cultivarCode,
                surveyDate.getYear(), surveyDate.getMonthValue(), surveyDate, reportedAt,
                cultivatedAreaMu, yieldPerMuKilograms, quality, costs, insurance, subsidies, submissionMetadata);
    }

    public static ProductionRecord draft(
            String id, String productCode, String objectTypeCode, String regionCode, String cultivarCode,
            int surveyYear, Integer surveyMonth, LocalDate surveyDate, OffsetDateTime reportedAt,
            BigDecimal cultivatedAreaMu, BigDecimal yieldPerMuKilograms, Map<String, BigDecimal> quality,
            Map<String, BigDecimal> costs, Map<String, BigDecimal> insurance, Map<String, BigDecimal> subsidies,
            Map<String, String> submissionMetadata) {
        BigDecimal area = input(cultivatedAreaMu, "cultivated area");
        BigDecimal yield = input(yieldPerMuKilograms, "yield per mu");
        return new ProductionRecord(id, productCode, objectTypeCode, regionCode, cultivarCode, surveyDate,
                surveyYear, surveyMonth,
                reportedAt, area, yield, area.multiply(yield).setScale(INPUT_SCALE, RoundingMode.HALF_UP),
                ProductionStatus.DRAFT, null, quality, costs, insurance, subsidies, submissionMetadata, 0);
    }

    public ProductionRecord revise(
            String nextProductCode, String nextObjectTypeCode, String nextRegionCode, String nextCultivarCode,
            LocalDate nextSurveyDate, OffsetDateTime nextReportedAt, BigDecimal nextArea, BigDecimal nextYield,
            Map<String, BigDecimal> nextQuality, Map<String, BigDecimal> nextCosts,
            Map<String, BigDecimal> nextInsurance, Map<String, BigDecimal> nextSubsidies) {
        return revise(nextProductCode, nextObjectTypeCode, nextRegionCode, nextCultivarCode, nextSurveyDate,
                nextReportedAt, nextArea, nextYield, nextQuality, nextCosts, nextInsurance, nextSubsidies,
                submissionMetadata);
    }

    public ProductionRecord revise(
            String nextProductCode, String nextObjectTypeCode, String nextRegionCode, String nextCultivarCode,
            LocalDate nextSurveyDate, OffsetDateTime nextReportedAt, BigDecimal nextArea, BigDecimal nextYield,
            Map<String, BigDecimal> nextQuality, Map<String, BigDecimal> nextCosts,
            Map<String, BigDecimal> nextInsurance, Map<String, BigDecimal> nextSubsidies,
            Map<String, String> nextSubmissionMetadata) {
        return revise(nextProductCode, nextObjectTypeCode, nextRegionCode, nextCultivarCode,
                nextSurveyDate.getYear(), nextSurveyDate.getMonthValue(), nextSurveyDate, nextReportedAt,
                nextArea, nextYield, nextQuality, nextCosts, nextInsurance, nextSubsidies, nextSubmissionMetadata);
    }

    public ProductionRecord revise(
            String nextProductCode, String nextObjectTypeCode, String nextRegionCode, String nextCultivarCode,
            int nextSurveyYear, Integer nextSurveyMonth, LocalDate nextSurveyDate, OffsetDateTime nextReportedAt,
            BigDecimal nextArea, BigDecimal nextYield, Map<String, BigDecimal> nextQuality,
            Map<String, BigDecimal> nextCosts, Map<String, BigDecimal> nextInsurance,
            Map<String, BigDecimal> nextSubsidies, Map<String, String> nextSubmissionMetadata) {
        if (status != ProductionStatus.DRAFT && status != ProductionStatus.RETURNED) {
            throw new IllegalStateException("Only DRAFT or RETURNED records may be revised");
        }
        BigDecimal area = input(nextArea, "cultivated area");
        BigDecimal yield = input(nextYield, "yield per mu");
        return new ProductionRecord(id, nextProductCode, nextObjectTypeCode, nextRegionCode, nextCultivarCode,
                nextSurveyDate, nextSurveyYear, nextSurveyMonth, nextReportedAt, area, yield,
                area.multiply(yield).setScale(INPUT_SCALE, RoundingMode.HALF_UP), status, returnReason,
                nextQuality, nextCosts, nextInsurance, nextSubsidies, nextSubmissionMetadata, version);
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

    public ProductionRecord voidRecord() {
        if (status != ProductionStatus.DRAFT && status != ProductionStatus.RETURNED) {
            throw new IllegalStateException("Cannot void production record from " + status);
        }
        return copy(ProductionStatus.VOIDED, null, version);
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
                surveyYear, surveyMonth, reportedAt, cultivatedAreaMu, yieldPerMuKilograms,
                estimatedOutputKilograms, next, reason,
                quality, costs, insurance, subsidies, submissionMetadata, nextVersion);
    }

    private static BigDecimal input(BigDecimal value, String description) {
        return decimal(value, MAX_INPUT, description);
    }

    private static BigDecimal decimal(BigDecimal value, BigDecimal maximum, String description) {
        if (value == null) throw invalid(description + " must not be null");
        BigDecimal normalized = value.setScale(INPUT_SCALE, RoundingMode.HALF_UP);
        if (normalized.signum() < 0) throw invalid(description + " must not be negative");
        if (normalized.compareTo(maximum) > 0 || normalized.precision() > INPUT_PRECISION + 4) {
            throw invalid(description + " exceeds database precision");
        }
        return normalized;
    }

    private static Map<String, BigDecimal> facts(Map<String, BigDecimal> values, String category) {
        Map<String, BigDecimal> normalized = new LinkedHashMap<>();
        if (values == null) throw invalid(category + " facts must not be null");
        values.forEach((code, value) -> {
            requireText(code, category + " fact code");
            normalized.put(code, input(value, category + " fact value"));
        });
        return Map.copyOf(normalized);
    }

    private static void requireText(String value, String description) {
        if (value == null || value.isBlank()) throw invalid(description + " must not be blank");
    }

    private static ProductionValidationException invalid(String message) {
        return new ProductionValidationException(message);
    }
}
