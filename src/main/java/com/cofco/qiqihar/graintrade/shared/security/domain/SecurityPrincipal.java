package com.cofco.qiqihar.graintrade.shared.security.domain;

import java.util.Set;

public record SecurityPrincipal(
        String subjectId, String workUnitCode, Set<String> permissionCodes, Set<String> regionCodes) {
    public SecurityPrincipal {
        permissionCodes = Set.copyOf(permissionCodes);
        regionCodes = Set.copyOf(regionCodes);
    }

    public boolean permits(String permissionCode) {
        return permissionCodes.contains(permissionCode);
    }

    public boolean includesRegion(String regionCode) {
        return regionCodes.contains(regionCode);
    }
}
