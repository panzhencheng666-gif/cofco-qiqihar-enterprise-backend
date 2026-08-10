package com.cofco.qiqihar.graintrade.market.application;

import java.time.LocalDate;
import java.util.List;

public record MarketObjectDraft(
        String objectName,
        String objectTypeId,
        String regionCode,
        List<String> productIds,
        List<String> cultivarIds,
        String sourceChannelId,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        String validityStatus,
        List<MarketObjectRoleDraft> roles) {
    public MarketObjectDraft {
        productIds = productIds == null ? List.of() : List.copyOf(productIds);
        cultivarIds = cultivarIds == null ? List.of() : List.copyOf(cultivarIds);
        roles = roles == null ? List.of() : List.copyOf(roles);
    }
}
