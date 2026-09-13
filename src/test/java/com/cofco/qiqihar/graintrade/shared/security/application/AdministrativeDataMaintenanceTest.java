package com.cofco.qiqihar.graintrade.shared.security.application;
import com.cofco.qiqihar.graintrade.shared.security.domain.SecurityPrincipal;
import com.cofco.qiqihar.graintrade.shared.application.AccessDeniedException;
import com.cofco.qiqihar.graintrade.designsample.point.application.*;
import com.cofco.qiqihar.graintrade.regionalproduction.application.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AdministrativeDataMaintenanceTest {
    private AccessControl employee() {
        var principal = new SecurityPrincipal("employee", "员工", "UNIT", "单位", "ACTIVE", "ACTIVE",
            Set.of("BUSINESS_OPERATOR"), List.of(),
            Set.of("BUSINESS_READ", "BUSINESS_CREATE", "BUSINESS_UPDATE", "FORMAL_SAMPLE_MANAGE"), Set.of("230202100"));
        return new AccessControl(() -> Optional.of("employee"), id -> Optional.of(principal), true);
    }
    @Test void administratorCapabilityDependsOnRoleNotUsername() {
        for (String role : List.of("SYSTEM_ADMIN", "BUSINESS_REVIEWER")) {
            var principal = new SecurityPrincipal("custom-admin", "管理", "UNIT", "单位", "ACTIVE", "ACTIVE",
                Set.of(role), List.of(), Set.of(), Set.of());
            var access = new AccessControl(() -> Optional.of(principal.subjectId()), id -> Optional.of(principal), true);
            assertThat(access.requireAdministrator()).isEqualTo(principal);
            assertThat(access.requireTaskReadScope().isUnrestricted()).isTrue();
        }
        assertThatThrownBy(() -> employee().requireAdministrator()).isInstanceOf(AccessDeniedException.class);
        assertThat(employee().requireBusinessReadScope().isUnrestricted()).isTrue();
        assertThat(employee().requireTaskReadScope().regionCodes()).containsExactly("230202100");
    }
    @Test void boundEmployeeCannotCreateUpdateOrDeleteDesignSample() {
        var service = new DesignSamplePointService(null,null,employee(),null,null,null);
        assertThatThrownBy(() -> service.create("request", null)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.update(UUID.randomUUID(),0,null)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.delete(UUID.randomUUID(),0)).isInstanceOf(AccessDeniedException.class);
    }
    @Test void boundEmployeeCannotDownloadOrImportDesignTemplate() {
        var service = new DesignSamplePointImportService(null,employee(),null,null,null,null,null,null);
        assertThatThrownBy(() -> service.template("MARKET")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.importFile("MARKET","request","sample.xlsx",null,new byte[0]))
            .isInstanceOf(AccessDeniedException.class);
    }
    @Test void boundEmployeeCannotMaintainRegionalProduction() {
        var service = new RegionalCropAnnualStatService(null,employee(),null,null,null);
        assertThatThrownBy(() -> service.upsert("230202",2026,"CORN",null,null,0))
            .isInstanceOf(AccessDeniedException.class);
    }
}
