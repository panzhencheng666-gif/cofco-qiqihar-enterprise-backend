package com.cofco.qiqihar.graintrade.market.domain;

import java.util.List;

public final class MarketActionPolicy {
    private MarketActionPolicy() {}
    public static List<String> allowedActions(MarketStatus status) {
        return status == MarketStatus.VOIDED ? List.of("VIEW") : List.of("VIEW", "SAVE", "VOID");
    }
}
