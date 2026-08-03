package com.cofco.qiqihar.graintrade.supply.application;

import java.util.List;
import java.util.Set;

public record SupplyInputSetMaterial(
        boolean contextExists,
        long currentVersion,
        Set<String> requiredRoles,
        List<Source> selectedSources) {

    public SupplyInputSetMaterial {
        requiredRoles = Set.copyOf(requiredRoles);
        selectedSources = List.copyOf(selectedSources);
    }

    public record Source(
            String releaseId,
            String roleCode,
            String sourceDomain,
            String sourceRecordId,
            long sourceVersion,
            String sourceFieldCode) {

        public String upstreamKey() {
            return sourceDomain + "|" + sourceRecordId + "|" + sourceVersion + "|" + sourceFieldCode;
        }
    }
}
