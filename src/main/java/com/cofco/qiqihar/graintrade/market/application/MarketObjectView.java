package com.cofco.qiqihar.graintrade.market.application;

import java.time.LocalDate;
import java.util.List;

public record MarketObjectView(
        String objectId,
        String objectName,
        String objectTypeId,
        String objectTypeLabel,
        String regionCode,
        String regionName,
        List<String> productIds,
        List<String> productLabels,
        List<String> cultivarIds,
        List<String> cultivarLabels,
        String sourceChannelId,
        String sourceChannelLabel,
        String responsibleUserId,
        String responsiblePerson,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        String validityStatus,
        List<Role> roles,
        long version) {
    public record Role(
            String roleId,
            String label,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String capabilityTemplateVersionId) {
    }
}
