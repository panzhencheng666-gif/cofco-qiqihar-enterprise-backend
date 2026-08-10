package com.cofco.qiqihar.graintrade.market.application;

import java.time.LocalDate;

public record MarketObjectRoleDraft(
        String roleId,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        String capabilityTemplateVersionId) {
}
