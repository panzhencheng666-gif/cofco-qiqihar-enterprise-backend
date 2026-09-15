package com.cofco.qiqihar.graintrade.production.domain;

import java.util.List;

public final class ProductionActionPolicy {
    private ProductionActionPolicy() { }

    public static List<String> allowedActions(ProductionStatus status) {
        return status == ProductionStatus.VOIDED ? List.of("VIEW") : List.of("VIEW", "SAVE", "VOID");
    }
}
