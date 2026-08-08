package com.cofco.qiqihar.graintrade.shared.security.application;

import com.cofco.qiqihar.graintrade.shared.application.AccessDeniedException;
import java.util.Set;

public record AuthorizedReadScope(String subjectId, Set<String> regionCodes) {
    private static final String UNRESTRICTED = "*";

    public AuthorizedReadScope {
        regionCodes = Set.copyOf(regionCodes);
    }

    public static AuthorizedReadScope unrestricted() {
        return new AuthorizedReadScope("", Set.of(UNRESTRICTED));
    }

    public boolean isUnrestricted() {
        return regionCodes.contains(UNRESTRICTED);
    }

    public void requireRegion(String regionCode) {
        if (!isUnrestricted() && !regionCodes.contains(regionCode)) {
            throw new AccessDeniedException(
                    "ACCESS_REGION_DENIED", "Data region is outside the assigned scope");
        }
    }
}
