package com.cofco.qiqihar.riskintelligence.security;

import java.util.Set;

/** Exact authoritative region grants; never expands ancestry or wildcard grants. */
public record RiskRegionScope(boolean rootAdministrator, Set<String> regionCodes) {
    public RiskRegionScope {
        regionCodes = regionCodes == null ? Set.of() : Set.copyOf(regionCodes);
        regionCodes.forEach(RiskRegionScope::requireRegionCode);
    }

    public static String requireRegionCode(String value) {
        if (value == null || !value.matches("[0-9]{6,12}")) {
            throw new IllegalArgumentException("regionCode must contain 6 to 12 ASCII digits");
        }
        return value;
    }

    public boolean hasAccess() { return rootAdministrator || !regionCodes.isEmpty(); }
}
