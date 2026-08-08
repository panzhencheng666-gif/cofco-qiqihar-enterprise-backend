package com.cofco.qiqihar.graintrade.shared.security.domain;

import java.util.Set;

public record SecurityPrincipal(
        String subjectId, String displayName, String workUnitCode,
        Set<String> permissionCodes, Set<String> regionCodes) {
    public SecurityPrincipal(
            String subjectId, String workUnitCode,
            Set<String> permissionCodes, Set<String> regionCodes) {
        this(subjectId, subjectId, workUnitCode, permissionCodes, regionCodes);
    }

    public SecurityPrincipal {
        if (subjectId == null || subjectId.isBlank()) {
            throw new IllegalArgumentException("Security subject id is required");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("Security subject display name is required");
        }
        permissionCodes = Set.copyOf(permissionCodes);
        regionCodes = Set.copyOf(regionCodes);
        if (regionCodes.contains("*")) {
            throw new IllegalArgumentException("Persisted region code must not use the unrestricted test sentinel");
        }
    }

    public boolean permits(String permissionCode) {
        return permissionCodes.contains(permissionCode);
    }

    public boolean includesRegion(String regionCode) {
        return regionCodes.contains(regionCode);
    }
}
