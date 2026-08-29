package com.cofco.qiqihar.graintrade.shared.security.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cofco.qiqihar.graintrade.shared.application.AuthenticationRequiredException;
import com.cofco.qiqihar.graintrade.shared.security.domain.SecurityPrincipal;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AccessControlTest {

    @Test
    void readAuthenticationConfigurationCannotDisableApplicationAuthorization() {
        AccessControl accessControl = new AccessControl(
                Optional::<String>empty,
                subjectId -> Optional.empty(),
                false);

        assertThatThrownBy(accessControl::requireReadScope)
                .isInstanceOf(AuthenticationRequiredException.class);
    }

    @Test
    void reusesTheValidatedPrincipalOnlyWithinTheCurrentRequestSubject() {
        AtomicInteger repositoryReads = new AtomicInteger();
        class RequestSubject implements CurrentSecuritySubject {
            private SecurityPrincipal cached;
            @Override public Optional<String> subjectId() { return Optional.of("reader"); }
            @Override public Optional<SecurityPrincipal> cachedPrincipal(String subjectId) {
                return Optional.ofNullable(cached)
                        .filter(principal -> principal.subjectId().equals(subjectId));
            }
            @Override public void cachePrincipal(SecurityPrincipal principal) { cached = principal; }
        }
        AccessControl accessControl = new AccessControl(
                new RequestSubject(),
                subjectId -> {
                    repositoryReads.incrementAndGet();
                    return Optional.of(new SecurityPrincipal(
                            subjectId, "TEST", Set.of("BUSINESS_READ"), Set.of("230200")));
                },
                true);

        assertThat(accessControl.requireReadScope().regionCodes()).containsExactly("230200");
        assertThat(accessControl.requireReadScope().regionCodes()).containsExactly("230200");
        assertThat(repositoryReads).hasValue(1);
    }
}
