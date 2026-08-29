package com.cofco.qiqihar.graintrade.shared.security.application;

import com.cofco.qiqihar.graintrade.shared.security.domain.SecurityPrincipal;
import java.util.Optional;

public interface CurrentSecuritySubject {
    Optional<String> subjectId();

    default Optional<SecurityPrincipal> cachedPrincipal(String subjectId) {
        return Optional.empty();
    }

    default void cachePrincipal(SecurityPrincipal principal) {
        // Non-request subjects deliberately do not retain authorization state.
    }
}
