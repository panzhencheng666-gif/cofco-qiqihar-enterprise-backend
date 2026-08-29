package com.cofco.qiqihar.graintrade.production.application;

import java.time.LocalDate;

public record ProductionObjectRoleDraft(
        String roleId,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        String capabilityTemplateVersionId) {
}
