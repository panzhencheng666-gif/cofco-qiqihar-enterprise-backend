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
    @Test
    void onlyTheBoundRootAdministratorBypassesOperationAndRegionGrants() {
        var admin=new SecurityPrincipal("admin","管理员","PLATFORM_ADMIN","平台系统管理",
                "ACTIVE","ACTIVE",Set.of("SYSTEM_ADMIN"),java.util.List.of(),Set.of(),Set.of());
        org.assertj.core.api.Assertions.assertThat(admin.permits("FUTURE_MODULE_OPERATION")).isTrue();
        org.assertj.core.api.Assertions.assertThat(admin.includesRegion("new-region")).isTrue();
        var ordinary=new SecurityPrincipal("admin","unit",Set.of(),Set.of());
        org.assertj.core.api.Assertions.assertThat(ordinary.permits("FUTURE_MODULE_OPERATION")).isFalse();
        var other=new SecurityPrincipal("other","管理员","unit","unit","ACTIVE","ACTIVE",
                Set.of("SYSTEM_ADMIN"),java.util.List.of(),Set.of(),Set.of());
        org.assertj.core.api.Assertions.assertThat(other.includesRegion("new-region")).isFalse();
    }
}
