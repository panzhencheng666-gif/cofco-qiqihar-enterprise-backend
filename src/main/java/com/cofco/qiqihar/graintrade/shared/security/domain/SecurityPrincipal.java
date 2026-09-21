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
        Set<String> regionCodes,
        List<RegionScope> assignedRegionScopes) {

    public SecurityPrincipal(
            String subjectId, String displayName, String workUnitCode, String workUnitName,
            String accountStatus, String employmentStatus, Set<String> roleCodes,
            List<PositionAssignment> positions, Set<String> permissionCodes, Set<String> regionCodes) {
        this(subjectId, displayName, workUnitCode, workUnitName, accountStatus, employmentStatus,
                roleCodes, positions, permissionCodes, regionCodes, List.of());
    }

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
        assignedRegionScopes = List.copyOf(assignedRegionScopes);
        if (regionCodes.contains("*")) {
            throw new IllegalArgumentException("Persisted region code must not use the unrestricted test sentinel");
        }
    }

    /** Root status comes from the bound business identity and its active role. */
    public boolean isRootAdministrator() {
        return hasAdministratorRole(roleCodes);
    }

    public static boolean hasAdministratorRole(java.util.Collection<String> roles) {
        return roles.contains("SYSTEM_ADMIN") || roles.contains("BUSINESS_REVIEWER");
    }

    /** Explicitly granted reporting access without a responsibility assignment. */
    public boolean isUnassignedReporter() {
        return regionCodes.isEmpty() && permissionCodes.contains("BUSINESS_UPDATE");
    }

    public boolean permits(String permissionCode) {
        return isRootAdministrator() || isSharedReportingPermission(permissionCode) || permissionCodes.contains(permissionCode);
    }

    private static final Set<String> SHARED_BUSINESS_PERMISSIONS = Set.of(
            "BUSINESS_READ", "BUSINESS_CREATE", "BUSINESS_UPDATE", "BUSINESS_IMPORT",
            "BUSINESS_SUBMIT", "BUSINESS_VOID", "FORMAL_SAMPLE_MANAGE", "FORMAL_SAMPLE_DELETE",
            "MARKET_OBJECT_MANAGE", "OBLIGATION_REPORT_READ", "OBLIGATION_REPORT_EXPORT", "REPORT_PREVIEW", "REPORT_EXPORT", "REPORT_PUBLISH");

    public static boolean isSharedReportingPermission(String permissionCode) {
        return SHARED_BUSINESS_PERMISSIONS.contains(permissionCode);
    }

    public boolean hasSharedReportingScope(String permissionCode) {
        return isSharedReportingPermission(permissionCode);
    }

    /** Effective business permissions; stored roles and responsibility assignments stay unchanged. */
    public Set<String> effectivePermissionCodes() {
        var effective = new java.util.HashSet<>(permissionCodes);
        effective.addAll(SHARED_BUSINESS_PERMISSIONS);
        return Set.copyOf(effective);
    }

    public boolean includesRegion(String regionCode) {
        return isRootAdministrator() || regionCodes.contains(regionCode);
    }

    public record PositionAssignment(String code, String name, boolean primaryPosition) {
        public PositionAssignment {
            if (code == null || code.isBlank() || name == null || name.isBlank()) {
                throw new IllegalArgumentException("Position code and name are required");
            }
        }
    }

    public record RegionScope(String code, String administrativeLevel, String namePath) {
        public RegionScope {
            if (code == null || code.isBlank() || administrativeLevel == null || administrativeLevel.isBlank()
                    || namePath == null || namePath.isBlank()) {
                throw new IllegalArgumentException("Assigned region code, level and name path are required");
            }
        }
    }
}
