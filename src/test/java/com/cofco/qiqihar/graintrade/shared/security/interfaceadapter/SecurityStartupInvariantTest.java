package com.cofco.qiqihar.graintrade.shared.security.interfaceadapter;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Test;

class SecurityStartupInvariantTest {

    @Test
    void rejectsLocalProfileOnWildcardAddress() {
        assertThatThrownBy(() -> SecurityStartupInvariant.validate(
                Set.of("local"), "0.0.0.0", "X-Actor", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("loopback");
    }

    @Test
    void rejectsTrustedSubjectHeaderOutsideLocalProfile() {
        assertThatThrownBy(() -> SecurityStartupInvariant.validate(
                Set.of("production"), "127.0.0.1", "X-Actor", "https://id.example.test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("trusted-subject-header")
                .hasMessageContaining("local");
    }

    @Test
    void rejectsMissingOidcIssuerInProduction() {
        assertThatThrownBy(() -> SecurityStartupInvariant.validate(
                Set.of("production"), "127.0.0.1", "", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("QIQIHAR_OIDC_ISSUER_URI");
    }

    @Test
    void acceptsLocalProfileOnLoopbackWithDevelopmentActorHeader() {
        assertThatCode(() -> SecurityStartupInvariant.validate(
                Set.of("local"), "127.0.0.1", "X-Actor", ""))
                .doesNotThrowAnyException();
    }
}
