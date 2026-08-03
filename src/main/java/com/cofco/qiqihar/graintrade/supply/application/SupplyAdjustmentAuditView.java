package com.cofco.qiqihar.graintrade.supply.application;

public record SupplyAdjustmentAuditView(
        String value,
        String reason,
        String actor,
        String decidedAt,
        long decisionVersion) {}
