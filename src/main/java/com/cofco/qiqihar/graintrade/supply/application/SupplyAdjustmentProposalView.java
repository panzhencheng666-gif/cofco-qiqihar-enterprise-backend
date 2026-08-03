package com.cofco.qiqihar.graintrade.supply.application;

public record SupplyAdjustmentProposalView(
        String value,
        String reason,
        String requestedBy,
        String requestedAt) {}
