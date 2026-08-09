package com.cofco.qiqihar.graintrade.shared.security.domain;

import java.util.List;
import java.util.Set;

public record SecurityPrincipal(
        String subjectId,
        String displayName,
        String workUnitCode,
        String workUnitName,
        String accountStatus,
        String employmentStatus,
        Set<String> roleCodes,
        List<PositionAssignment> positions,
        Set<String> permissionCodes,
        Set<String> regionCodes) {

    public SecurityPrincipal(String subjectId, String displayName, String workUnitCode,
            Set<String> permissionCodes, Set<String> regionCodes) {
        this(subjectId, displayName, workUnitCode, workUnitCode, "ACTIVE", "ACTIVE",
                Set.of(), List.of(), permissionCodes, regionCodes);
    }

    public SecurityPrincipal(String subjectId, String workUnitCode,
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
        if (workUnitCode == null || workUnitCode.isBlank() || workUnitName == null || workUnitName.isBlank()) {
            throw new IllegalArgumentException("Security subject work unit is required");
        }
        roleCodes = Set.copyOf(roleCodes);
        positions = List.copyOf(positions);
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

    public record PositionAssignment(String code, String name, boolean primaryPosition) {
        public PositionAssignment {
            if (code == null || code.isBlank() || name == null || name.isBlank()) {
                throw new IllegalArgumentException("Position code and name are required");
            }
        }
    }
}
