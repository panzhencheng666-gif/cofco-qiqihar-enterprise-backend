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
    void mapReadIsSharedWhileWritesKeepResponsibilityScope() {
        var user = new SecurityPrincipal("reader", "TEST",
                Set.of("BUSINESS_READ", "BUSINESS_UPDATE"), Set.of("230200"));
        var access = new AccessControl(() -> Optional.of("reader"), id -> Optional.of(user), true);
        assertThat(access.requireOverviewReadScope().isUnrestricted()).isTrue();
        assertThat(access.requireOverviewReadScope().subjectId()).isEqualTo("reader");
        assertThat(access.requireReadScope().regionCodes()).containsExactly("230200");
        assertThatThrownBy(() -> access.require("BUSINESS_UPDATE", "231100"))
                .isInstanceOf(com.cofco.qiqihar.graintrade.shared.application.AccessDeniedException.class);
    }

    @Test
    void enabledAccountWithoutAssignedRegionsCanViewMapButAnonymousCannot() {
        var user = new SecurityPrincipal("reader", "TEST", Set.of(), Set.of());
        var access = new AccessControl(() -> Optional.of("reader"), id -> Optional.of(user), true);
        assertThat(access.requireOverviewReadScope().isUnrestricted()).isTrue();
        var anonymous = new AccessControl(Optional::<String>empty, id -> Optional.empty(), true);
        assertThatThrownBy(anonymous::requireOverviewReadScope)
                .isInstanceOf(AuthenticationRequiredException.class);
    }

    @Test
    void rootReadsAllRegionsAndDoesNotRequireEmployeeResponsibility() {
        var root = new SecurityPrincipal("admin", "Admin", "PLATFORM_ADMIN", "Platform", "ACTIVE", "ACTIVE",
                Set.of("SYSTEM_ADMIN"), java.util.List.of(), Set.of(), Set.of("outside-unit"));
        var repository = new SecurityPrincipalRepository() {
            public Optional<SecurityPrincipal> findEnabled(String id) { return Optional.of(root); }
            public Optional<String> responsibleSubject(String region, boolean county) {
                throw new AssertionError("Root must not be checked as an employee maintainer");
            }
        };
        var access = new AccessControl(() -> Optional.of("admin"), repository, true);
        assertThat(access.requireReadScope().regionCodes()).containsExactly("outside-unit");
        assertThat(access.requireReadScope().subjectId()).isEqualTo("admin");
        assertThat(access.require("BUSINESS_UPDATE", "outside-unit")).isEqualTo(root);
        access.requireCountyReporter(root, "outside-unit");
    }


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
