package com.cofco.qiqihar.graintrade.market.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

/** Normalized market aggregate; values are stored as rows, never as a source-of-truth JSON document. */
public record MarketMonitoringRecord(
        String id, String productCode, String objectTypeCode, String regionCode, LocalDate tradeDate,
        OffsetDateTime reportedAt, MarketTradeDirection direction, BigDecimal purchaseBasePrice,
        BigDecimal saleBasePrice, BigDecimal carriageBoardAmount, BigDecimal freightAmount,
        BigDecimal packagingAmount, String packagingForm, BigDecimal actualTradePrice, MarketStatus status, String returnReason,
        Map<String, BigDecimal> facts, long version) {
    private static final ZoneId REPORTING_ZONE = ZoneId.of("Asia/Shanghai");
    public MarketMonitoringRecord {
        text(id, "id"); text(productCode, "product code"); text(objectTypeCode, "object type");
        text(regionCode, "region"); if (tradeDate == null || reportedAt == null || direction == null || status == null) {
            throw invalid("market record context must not be null");
        }
        if (tradeDate.isAfter(reportedAt.atZoneSameInstant(REPORTING_ZONE).toLocalDate())) {
            throw invalid("trade date cannot be after reported date");
        }
        purchaseBasePrice = optional(purchaseBasePrice, "purchase base price");
        saleBasePrice = optional(saleBasePrice, "sale base price");
        boolean observationOnly = direction == MarketTradeDirection.OBSERVATION;
        carriageBoardAmount = observationOnly ? absent(carriageBoardAmount, "carriage-board amount")
                : optional(carriageBoardAmount, "carriage-board amount");
        packagingAmount = observationOnly ? absent(packagingAmount, "packaging amount")
                : optional(packagingAmount, "packaging amount");
        freightAmount = observationOnly ? absent(freightAmount, "freight amount")
                : optional(freightAmount, "freight amount");
        if (observationOnly) {
            if (packagingForm != null) throw invalid("packaging form is not applicable");
        } else {
            if (packagingForm != null && !packagingForm.equals("BULK") && !packagingForm.equals("BAGGED")) {
                throw invalid("packaging form is invalid");
            }
        }
        if (direction == MarketTradeDirection.PURCHASE && purchaseBasePrice == null) throw invalid("purchase base price is required");
        if (direction == MarketTradeDirection.SALE && saleBasePrice == null) throw invalid("sale base price is required");
        if (direction == MarketTradeDirection.BOTH
                && (purchaseBasePrice == null || saleBasePrice == null)) {
            throw invalid("purchase and sale prices are required");
        }
        BigDecimal calculated = MarketPricing.actualPrice(direction, purchaseBasePrice, saleBasePrice,
                carriageBoardAmount, packagingAmount, freightAmount);
        actualTradePrice = observationOnly ? absent(actualTradePrice, "actual trade price")
                : MarketPricing.amount(actualTradePrice, "actual trade price");
        if (!observationOnly && calculated.compareTo(actualTradePrice) != 0) throw invalid("actual trade price must equal base plus applicable components");
        if (status == MarketStatus.RETURNED && (returnReason == null || returnReason.isBlank())) throw invalid("return reason is required");
        if (status != MarketStatus.RETURNED && returnReason != null) throw invalid("return reason only applies to returned records");
        Map<String, BigDecimal> normalized = new LinkedHashMap<>();
        if (facts == null) throw invalid("facts must not be null");
        facts.forEach((code, value) -> { text(code, "fact code"); normalized.put(code, MarketPricing.amount(value, "fact value")); });
        facts = Map.copyOf(normalized);
        if (version < 0) throw invalid("version must not be negative");
    }
    public static MarketMonitoringRecord draft(String id, String product, String object, String region,
            LocalDate date, OffsetDateTime reported, MarketTradeDirection direction, BigDecimal purchase,
            BigDecimal sale, BigDecimal carriage, BigDecimal packagingAmount, BigDecimal freight,
            String packaging, Map<String, BigDecimal> facts) {
        return create(id, product, object, region, date, reported, direction, purchase, sale, carriage, packagingAmount, freight,
                packaging, MarketStatus.DRAFT, null, facts, 0);
    }
    public MarketMonitoringRecord revise(String object, String region, LocalDate date, OffsetDateTime reported,
            MarketTradeDirection nextDirection, BigDecimal purchase, BigDecimal sale, BigDecimal carriage,
            BigDecimal packagingAmount, BigDecimal freight, String packaging, Map<String, BigDecimal> nextFacts) {
        if (status != MarketStatus.DRAFT && status != MarketStatus.RETURNED) throw new IllegalStateException("Only DRAFT or RETURNED records may be revised");
        return create(id, productCode, object, region, date, reported, nextDirection, purchase, sale, carriage,
                packagingAmount, freight, packaging, status, returnReason, nextFacts, version);
    }
    public MarketMonitoringRecord submit() { return transition(MarketStatus.DRAFT, MarketStatus.PENDING_REVIEW, null, true); }
    public MarketMonitoringRecord approve() { return transition(MarketStatus.PENDING_REVIEW, MarketStatus.APPROVED, null, false); }
    public MarketMonitoringRecord returnForCorrection(String reason) { return transition(MarketStatus.PENDING_REVIEW, MarketStatus.RETURNED, reason, false); }
    public MarketMonitoringRecord voidRecord() {
        if (status != MarketStatus.DRAFT && status != MarketStatus.RETURNED)
            throw new IllegalStateException("Only DRAFT or RETURNED market records may be voided");
        return new MarketMonitoringRecord(id, productCode, objectTypeCode, regionCode, tradeDate, reportedAt,
                direction, purchaseBasePrice, saleBasePrice, carriageBoardAmount, freightAmount,
                packagingAmount, packagingForm, actualTradePrice, MarketStatus.VOIDED, null, facts, version);
    }
    public MarketMonitoringRecord savedAsVersion(long nextVersion) { return new MarketMonitoringRecord(id, productCode, objectTypeCode, regionCode, tradeDate, reportedAt, direction, purchaseBasePrice, saleBasePrice, carriageBoardAmount, freightAmount, packagingAmount, packagingForm, actualTradePrice, status, returnReason, facts, nextVersion); }
    private MarketMonitoringRecord transition(MarketStatus expected, MarketStatus next, String reason, boolean revisableReturned) {
        if ((revisableReturned && status != MarketStatus.DRAFT && status != MarketStatus.RETURNED) || (!revisableReturned && status != expected)) throw new IllegalStateException("Invalid market record transition from " + status + " to " + next);
        if (next == MarketStatus.RETURNED && (reason == null || reason.isBlank())) throw invalid("return reason is required");
        return new MarketMonitoringRecord(id, productCode, objectTypeCode, regionCode, tradeDate, reportedAt, direction, purchaseBasePrice, saleBasePrice, carriageBoardAmount, freightAmount, packagingAmount, packagingForm, actualTradePrice, next, next == MarketStatus.RETURNED ? reason : null, facts, version);
    }
    private static MarketMonitoringRecord create(String id,String product,String object,String region,LocalDate date,OffsetDateTime reported,MarketTradeDirection direction,BigDecimal purchase,BigDecimal sale,BigDecimal carriage,BigDecimal packagingAmount,BigDecimal freight,String packaging,MarketStatus status,String reason,Map<String,BigDecimal> facts,long version) {
        BigDecimal actual = MarketPricing.actualPrice(direction, purchase, sale, carriage, packagingAmount, freight);
        return new MarketMonitoringRecord(id, product, object, region, date, reported, direction, purchase, sale, carriage, freight, packagingAmount, packaging, actual, status, reason, facts, version);
    }
    private static BigDecimal optional(BigDecimal value, String description) { return value == null ? null : MarketPricing.amount(value, description); }
    private static BigDecimal absent(BigDecimal value, String description) {
        if (value != null) throw invalid(description + " is not applicable");
        return null;
    }
    private static void text(String value, String description) { if (value == null || value.isBlank()) throw invalid(description + " must not be blank"); }
    private static MarketValidationException invalid(String message) { return new MarketValidationException(message); }
}
