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
    void unassignedReporterCanSubmitOnlyWithAnExplicitSubmissionGrant() {
        var reporter = new SecurityPrincipal("reporter", "TEST", Set.of("BUSINESS_UPDATE", "BUSINESS_SUBMIT"), Set.of());
        var access = new AccessControl(() -> Optional.of("reporter"), id -> Optional.of(reporter), true);
        assertThat(access.require("BUSINESS_SUBMIT", "230200")).isEqualTo(reporter);
        assertThat(access.require("BUSINESS_SUBMIT", "231100")).isEqualTo(reporter);
        for (String permission : Set.of("BUSINESS_APPROVE", "BUSINESS_RETURN", "MASTER_DATA_APPLY")) {
            assertThatThrownBy(() -> access.require(permission, "230200"))
                    .isInstanceOf(com.cofco.qiqihar.graintrade.shared.application.AccessDeniedException.class);
        }
        for (Set<String> grants : java.util.List.of(Set.of("BUSINESS_UPDATE"), Set.of("BUSINESS_SUBMIT"))) {
            var limited = new SecurityPrincipal("reporter", "TEST", grants, Set.of());
            var limitedAccess = new AccessControl(() -> Optional.of("reporter"), id -> Optional.of(limited), true);
            assertThatThrownBy(() -> limitedAccess.require("BUSINESS_SUBMIT", "230200"))
                    .isInstanceOf(com.cofco.qiqihar.graintrade.shared.application.AccessDeniedException.class);
        }
    }

    @Test
    void assignedReporterStillSubmitsOnlyInsideTheAssignedRegion() {
        var reporter = new SecurityPrincipal("reporter", "TEST", Set.of("BUSINESS_UPDATE", "BUSINESS_SUBMIT"), Set.of("230200"));
        var access = new AccessControl(() -> Optional.of("reporter"), id -> Optional.of(reporter), true);
        assertThat(access.require("BUSINESS_SUBMIT", "230200")).isEqualTo(reporter);
        assertThatThrownBy(() -> access.require("BUSINESS_SUBMIT", "231100"))
                .isInstanceOf(com.cofco.qiqihar.graintrade.shared.application.AccessDeniedException.class)
                .hasMessage("Data region is outside the assigned scope");
    }

    @Test
    void unassignedReporterUsesAdministratorTaskCoverageWithoutAdministratorPowers() {
        var reporter = new SecurityPrincipal("reporter", "TEST", Set.of("BUSINESS_UPDATE", "BUSINESS_SUBMIT"), Set.of());
        var access = new AccessControl(() -> Optional.of("reporter"), id -> Optional.of(reporter), true);
        assertThat(access.requireTaskReadScope().isUnrestricted()).isTrue();
        for (String region : java.util.Arrays.asList("230200", "231100", null, "")) {
            assertThat(access.requireBusinessVoid(region)).isEqualTo(reporter);
        }
        assertThatThrownBy(access::requireAdministrator)
                .isInstanceOf(com.cofco.qiqihar.graintrade.shared.application.AccessDeniedException.class);
        assertThatThrownBy(() -> access.require("BUSINESS_APPROVE", "230200"))
                .isInstanceOf(com.cofco.qiqihar.graintrade.shared.application.AccessDeniedException.class);
    }

    @Test
    void unassignedEnabledAccountCanReadCreateAndUpdateAcrossRegionsWithoutTakingResponsibility() {
        var user = new SecurityPrincipal("reader", "TEST", Set.of(), Set.of());
        var repository = new SecurityPrincipalRepository() {
            public Optional<SecurityPrincipal> findEnabled(String id) { return Optional.of(user); }
            public Optional<String> responsibleSubject(String region, boolean county) {
                throw new AssertionError("Reporting must not change or check the assigned owner");
            }
        };
        var access = new AccessControl(() -> Optional.of("reader"), repository, true);
        for (String permission : Set.of("BUSINESS_READ", "BUSINESS_CREATE", "BUSINESS_UPDATE")) {
            assertThat(access.require(permission, "231100")).isEqualTo(user);
        }
        access.requireCountyReporter(user, "231100");
        assertThat(access.requireBusinessReadScope().isUnrestricted()).isTrue();
        assertThat(access.requireTaskReadScope().regionCodes()).isEmpty();
        assertThatThrownBy(access::requireAdministrator)
                .isInstanceOf(com.cofco.qiqihar.graintrade.shared.application.AccessDeniedException.class);
        assertThatThrownBy(() -> access.require("BUSINESS_APPROVE", "231100"))
                .isInstanceOf(com.cofco.qiqihar.graintrade.shared.application.AccessDeniedException.class);
    }

    @Test
    void voidRequiresStoredPermissionRegionAndResponsibility() {
        var reader = new SecurityPrincipal("reader", "TEST", Set.of(), Set.of("230200"));
        var writer = new SecurityPrincipal("writer", "TEST", Set.of("BUSINESS_UPDATE"), Set.of("230200"));
        var current = new java.util.concurrent.atomic.AtomicReference<>(reader);
        var owner = new java.util.concurrent.atomic.AtomicReference<>("writer");
        var repository = new SecurityPrincipalRepository() {
            public Optional<SecurityPrincipal> findEnabled(String id) { return Optional.of(current.get()); }
            public Optional<String> responsibleSubject(String region, boolean county) { return Optional.ofNullable(owner.get()); }
        };
        var access = new AccessControl(() -> Optional.of(current.get().subjectId()), repository, true);
        assertThatThrownBy(() -> access.requireBusinessVoid("230200"))
                .isInstanceOf(com.cofco.qiqihar.graintrade.shared.application.AccessDeniedException.class);
        current.set(writer);
        assertThat(access.requireBusinessVoid("230200")).isEqualTo(writer);
        assertThatThrownBy(() -> access.requireBusinessVoid("231100"))
                .isInstanceOf(com.cofco.qiqihar.graintrade.shared.application.AccessDeniedException.class);
        owner.set("other");
        assertThatThrownBy(() -> access.requireBusinessVoid("230200"))
                .isInstanceOf(com.cofco.qiqihar.graintrade.shared.application.AccessDeniedException.class);
        owner.set(null);
        assertThat(access.requireBusinessVoid("230200")).isEqualTo(writer);
    }

    @Test
    void reportingStillRejectsAnonymousAndDisabledAccounts() {
        var anonymous = new AccessControl(Optional::<String>empty, id -> Optional.empty(), true);
        var disabled = new AccessControl(() -> Optional.of("disabled"), id -> Optional.empty(), true);
        for (String permission : Set.of("BUSINESS_READ", "BUSINESS_CREATE", "BUSINESS_UPDATE")) {
            assertThatThrownBy(() -> anonymous.require(permission, "231100"))
                    .isInstanceOf(AuthenticationRequiredException.class);
            assertThatThrownBy(() -> disabled.require(permission, "231100"))
                    .isInstanceOf(com.cofco.qiqihar.graintrade.shared.application.AccessDeniedException.class);
        }
    }

    @Test
    void businessWritesAreSharedWhileTaskAssignmentsKeepTheirScope() {
        var user = new SecurityPrincipal("reader", "TEST",
                Set.of("BUSINESS_READ", "BUSINESS_UPDATE"), Set.of("230200"));
        var access = new AccessControl(() -> Optional.of("reader"), id -> Optional.of(user), true);
        assertThat(access.requireOverviewReadScope().isUnrestricted()).isTrue();
        assertThat(access.requireOverviewReadScope().subjectId()).isEqualTo("reader");
        assertThat(access.requireReadScope().regionCodes()).containsExactly("230200");
        assertThat(access.require("BUSINESS_UPDATE", "231100")).isEqualTo(user);
        assertThat(access.requireTaskReadScope().regionCodes()).containsExactly("230200");
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
