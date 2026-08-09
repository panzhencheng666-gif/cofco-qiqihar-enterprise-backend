package com.cofco.qiqihar.graintrade.logistics.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

public record LogisticsRecord(
        String id, String productCode, String monitoringPeriodCode, LocalDate collectionDate,
        OffsetDateTime reportedAt, String originNodeCode, String destinationNodeCode,
        String transportModeCode, String directionCode, BigDecimal routeVolume,
        BigDecimal freightRate, BigDecimal transitTimeHours, LogisticsStatus status,
        String returnReason, long version) {
    public static LogisticsRecord draft(String id, String productCode, String monitoringPeriodCode,
            LocalDate collectionDate, OffsetDateTime reportedAt, String originNodeCode,
            String destinationNodeCode, String transportModeCode, String directionCode,
            BigDecimal routeVolume, BigDecimal freightRate, BigDecimal transitTimeHours) {
        if (id == null || id.isBlank() || productCode == null || productCode.isBlank()
                || monitoringPeriodCode == null || monitoringPeriodCode.isBlank()
                || collectionDate == null || reportedAt == null || originNodeCode == null
                || destinationNodeCode == null || originNodeCode.equals(destinationNodeCode)
                || transportModeCode == null || directionCode == null || routeVolume == null
                || routeVolume.signum() < 0 || freightRate == null || freightRate.signum() < 0
                || transitTimeHours == null || transitTimeHours.signum() < 0) {
            throw new IllegalArgumentException("Invalid logistics record");
        }
        return new LogisticsRecord(id, productCode, monitoringPeriodCode, collectionDate, reportedAt,
                originNodeCode, destinationNodeCode, transportModeCode, directionCode, routeVolume,
                freightRate, transitTimeHours, LogisticsStatus.DRAFT, null, 0);
    }

    public LogisticsRecord submit() { return transition(LogisticsStatus.PENDING_REVIEW, null); }
    public LogisticsRecord approve() {
        if (status != LogisticsStatus.PENDING_REVIEW) throw new IllegalStateException("Only pending records can be approved");
        return transition(LogisticsStatus.APPROVED, null);
    }
    public LogisticsRecord returnForCorrection(String reason) {
        if (status != LogisticsStatus.PENDING_REVIEW || reason == null || reason.isBlank())
            throw new IllegalArgumentException("A pending record and return reason are required");
        return transition(LogisticsStatus.RETURNED, reason.trim());
    }
    public LogisticsRecord revise() {
        if (status != LogisticsStatus.RETURNED) throw new IllegalStateException("Only returned records can be revised");
        return transition(LogisticsStatus.RETURNED, returnReason);
    }
    private LogisticsRecord transition(LogisticsStatus next, String reason) {
        if (next == LogisticsStatus.PENDING_REVIEW
                && status != LogisticsStatus.DRAFT && status != LogisticsStatus.RETURNED)
            throw new IllegalStateException("Only drafts or returned records can be submitted");
        return new LogisticsRecord(id, productCode, monitoringPeriodCode, collectionDate, reportedAt,
                originNodeCode, destinationNodeCode, transportModeCode, directionCode, routeVolume,
                freightRate, transitTimeHours, next, reason, version);
    }
}
