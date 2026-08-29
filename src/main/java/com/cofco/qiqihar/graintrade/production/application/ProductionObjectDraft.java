package com.cofco.qiqihar.graintrade.production.application;

import java.time.LocalDate;
import java.util.List;

public record ProductionObjectDraft(
        String objectName,
        String objectTypeId,
        String regionCode,
        List<String> productIds,
        List<String> cultivarIds,
        String sourceChannelId,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        String validityStatus,
        List<ProductionObjectRoleDraft> roles) {
    public ProductionObjectDraft {
        productIds = productIds == null ? List.of() : List.copyOf(productIds);
        cultivarIds = cultivarIds == null ? List.of() : List.copyOf(cultivarIds);
        roles = roles == null ? List.of() : List.copyOf(roles);
    }
}
