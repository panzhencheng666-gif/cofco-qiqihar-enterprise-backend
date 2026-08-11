package com.cofco.qiqihar.graintrade.market.domain;

import java.util.List;

public final class MarketActionPolicy {
    private MarketActionPolicy() {}
    public static List<String> allowedActions(MarketStatus status) {
        return switch (status) {
            case DRAFT, RETURNED -> List.of("VIEW", "SAVE", "SUBMIT", "VOID");
            case PENDING_REVIEW -> List.of("VIEW", "APPROVE", "RETURN");
            case APPROVED, VOIDED -> List.of("VIEW");
        };
    }
}
