package com.cofco.qiqihar.graintrade.production.domain;

import java.util.List;

public final class ProductionActionPolicy {
    private ProductionActionPolicy() { }

    public static List<String> allowedActions(ProductionStatus status) {
        return switch (status) {
            case DRAFT, RETURNED -> List.of("VIEW", "SAVE", "SUBMIT");
            case PENDING_REVIEW -> List.of("VIEW", "APPROVE", "RETURN");
            case APPROVED -> List.of("VIEW");
        };
    }
}
