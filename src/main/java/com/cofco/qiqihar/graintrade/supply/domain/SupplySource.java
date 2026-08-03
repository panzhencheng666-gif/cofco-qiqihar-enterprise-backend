package com.cofco.qiqihar.graintrade.supply.domain;

import java.math.BigDecimal;

public record SupplySource(
        String role, String sourceDomain, String sourceRecordId, long sourceVersion,
        ApprovalState approvalState, QualityState qualityState, BigDecimal adoptedValue,
        String adoptionReason, String drillDownRoute) {
    public SupplySource {
        if (role == null || role.isBlank() || sourceDomain == null || sourceDomain.isBlank()
                || sourceRecordId == null || sourceRecordId.isBlank() || sourceVersion < 0
                || approvalState == null || qualityState == null || adoptedValue == null
                || adoptionReason == null || adoptionReason.isBlank()
                || drillDownRoute == null || drillDownRoute.isBlank()) {
            throw new IllegalArgumentException("Invalid supply source");
        }
    }
}
