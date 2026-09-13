package com.cofco.qiqihar.graintrade.identity.application;

import java.util.List;
import org.junit.jupiter.api.Test;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AccountRegionLimitTest {
    private static final List<String> ELEVEN=List.of("1","2","3","4","5","6","7","8","9","10","11");
    @Test void governanceRejectsElevenRegionsEvenWhenCatalogAllowsThem() {
        var repository=mock(IdentityGovernanceRepository.class);
        when(repository.validAssignment(any())).thenReturn(true);
        var service=new IdentityGovernanceService(repository,null,null,null,null,null);
        assertThatThrownBy(()->service.validateRegistration(new EmployeeAssignment("员工","QIQIHAR_BUSINESS",
                "ACTIVE","ACTIVE",List.of("BUSINESS_OPERATOR"),List.of(),ELEVEN)))
                .isInstanceOf(ClientRequestException.class).hasMessageContaining("10");
        assertThatCode(()->service.validateRegistration(new EmployeeAssignment("员工","QIQIHAR_BUSINESS",
                "ACTIVE","ACTIVE",List.of("BUSINESS_OPERATOR"),List.of(),ELEVEN.subList(0,10)))).doesNotThrowAnyException();
    }
    @Test void onlyAdministratorRoleIsExemptAndDuplicateSourcesCountOnce() {
        assertThatCode(()->AccountRegionPolicy.requireAtMostTen("admin",ELEVEN,true)).doesNotThrowAnyException();
        for(String subject:List.of("admin","Admin","system-admin","employee"))
            assertThatThrownBy(()->AccountRegionPolicy.requireAtMostTen(subject,ELEVEN)).isInstanceOf(ClientRequestException.class);
        assertThatCode(()->AccountRegionPolicy.requireAtMostTen("employee",List.of("1","2","3","4","5","1"))).doesNotThrowAnyException();
    }
    @Test void responsibilityRejectsElevenForOrdinaryAccounts() {
        var repository=mock(RegionResponsibilityRepository.class);
        var identities=mock(IdentityGovernanceService.class);
        var principals=mock(com.cofco.qiqihar.graintrade.shared.security.application.SecurityPrincipalRepository.class);
        var access=mock(com.cofco.qiqihar.graintrade.shared.security.application.AccessControl.class);
        var actor=mock(com.cofco.qiqihar.graintrade.shared.security.domain.SecurityPrincipal.class);
        when(actor.subjectId()).thenReturn("actor");when(actor.permits(anyString())).thenReturn(true);
        when(actor.workUnitCode()).thenReturn("UNIT");
        when(access.require("IDENTITY_ADMIN",null)).thenReturn(actor);
        when(principals.findEnabled("actor")).thenReturn(java.util.Optional.of(actor));
        var target=mock(com.cofco.qiqihar.graintrade.shared.security.domain.SecurityPrincipal.class);
        when(target.permits("BUSINESS_CREATE")).thenReturn(true);
        when(principals.findEnabled("employee")).thenReturn(java.util.Optional.of(target));
        var employee=mock(EmployeeProfile.class);when(employee.workUnitCode()).thenReturn("UNIT");when(employee.regionCodes()).thenReturn(List.of());
        when(identities.employee("employee")).thenReturn(employee);
        var options=mock(AssignmentOptions.class);when(options.regionCodes()).thenReturn(ELEVEN);
        when(identities.assignmentOptions("UNIT","employee")).thenReturn(options);
        var service=new RegionResponsibilityService(repository,identities,principals,access,null,null,null);
        assertThatThrownBy(()->service.preview("employee",ELEVEN))
                .isInstanceOf(ClientRequestException.class).hasMessageContaining("10");
    }
}
