package com.cofco.qiqihar.graintrade.identity.application;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import com.cofco.qiqihar.graintrade.shared.security.domain.SecurityPrincipal;
import com.cofco.qiqihar.graintrade.shared.security.application.AccessControl;
import com.cofco.qiqihar.graintrade.shared.application.AccessDeniedException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdministratorParityTest {
    private SecurityPrincipal principal(String subject,String role) {
        return new SecurityPrincipal(subject,subject,"QIQIHAR_BUSINESS","单位","ACTIVE","ACTIVE",Set.of(role),List.of(),Set.of(),Set.of());
    }
    @Test void independentlyNamedAdministratorsHaveFullPermissionAndRegionParity() {
        for(String role:List.of("SYSTEM_ADMIN","BUSINESS_REVIEWER")) {
            var administrator=principal("another-administrator",role);
            assertThat(administrator.isRootAdministrator()).isTrue();
            assertThat(administrator.permits("IDENTITY_ADMIN")).isTrue();
            assertThat(administrator.permits("FUTURE_PERMISSION")).isTrue();
            assertThat(administrator.includesRegion("230202001")).isTrue();
        }
    }
    @Test void usernameAloneCannotGrantAdministratorPermissions() {
        assertThat(principal("admin","BUSINESS_OPERATOR").isRootAdministrator()).isFalse();
        assertThat(principal("ordinary","BUSINESS_OPERATOR").permits("IDENTITY_ADMIN")).isFalse();
    }
    @Test void delegatedIdentityPermissionCannotCreateFullAdministrators() {
        var access=mock(AccessControl.class);
        when(access.require("IDENTITY_ADMIN",null)).thenReturn(principal("delegated","IDENTITY_ADMIN"));
        var service=new IdentityGovernanceService(null,access,null,null,null,null);
        assertThatThrownBy(()->service.invite("idempotency-123","new-employee","staff@example.com",
            new EmployeeAssignment("员工","QIQIHAR_BUSINESS","INVITED","ACTIVE",List.of("BUSINESS_REVIEWER"),List.of(),List.of("230202001"))))
            .isInstanceOf(AccessDeniedException.class).hasMessageContaining("管理员");
    }
}
