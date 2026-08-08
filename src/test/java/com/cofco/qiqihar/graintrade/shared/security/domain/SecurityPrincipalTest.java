package com.cofco.qiqihar.graintrade.shared.security.domain;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Test;

class SecurityPrincipalTest {

    @Test
    void persistedWildcardRegionCannotBecomeUnrestrictedProductionScope() {
        assertThatThrownBy(() -> new SecurityPrincipal(
                "subject", "unit", Set.of("BUSINESS_READ"), Set.of("*")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("region code");
    }
}
