package com.cofco.qiqihar.graintrade.formalsamplepoint.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cofco.qiqihar.graintrade.samplepoint.coordinate.application.SamplePointCoordinateGuard;
import com.cofco.qiqihar.graintrade.shared.application.AccessDeniedException;
import com.cofco.qiqihar.graintrade.shared.audit.application.BusinessAuditRecorder;
import com.cofco.qiqihar.graintrade.shared.security.application.AccessControl;
import com.cofco.qiqihar.graintrade.shared.security.application.SecurityPrincipalRepository;
import com.cofco.qiqihar.graintrade.shared.security.domain.SecurityPrincipal;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class AdministratorSampleScopeTest {
    @Test
    void bothAdministratorIdentitiesEditSamplesOutsideTheirAssignedRegionAndOwnership() {
        for (String subject : List.of("admin", "wangqingwen")) {
            for (String role : List.of("SYSTEM_ADMIN", "BUSINESS_REVIEWER")) {
                verifyEdit(subject, role, true);
            }
        }
    }

    @Test
    void ordinaryIdentityStillCannotEditOutsideAssignedRegion() {
        verifyEdit("ordinary", "BUSINESS_OPERATOR", false);
    }

    private void verifyEdit(String subject, String role, boolean allowed) {
        var actor = new SecurityPrincipal(subject, subject, "TEST", "Test", "ACTIVE", "ACTIVE",
                Set.of(role), List.of(), Set.of("BUSINESS_READ", "BUSINESS_CREATE"), Set.of("230202"));
        var owner = new SecurityPrincipal("other-owner", "TEST",
                Set.of("BUSINESS_READ", "BUSINESS_CREATE"), Set.of("231102"));
        var principals = mock(SecurityPrincipalRepository.class);
        when(principals.findEnabled(subject)).thenReturn(Optional.of(actor));
        when(principals.findEnabled("other-owner")).thenReturn(Optional.of(owner));
        var access = new AccessControl(() -> Optional.of(subject), principals, true);
        var repository = mock(FormalSamplePointRepository.class);
        var point = mock(FormalSamplePointView.class);
        UUID id = UUID.randomUUID();
        when(point.id()).thenReturn(id);
        when(point.regionCode()).thenReturn("231102");
        when(point.maintainerSubjectId()).thenReturn("other-owner");
        when(point.version()).thenReturn(3L);
        when(repository.find(id)).thenReturn(Optional.of(point));
        when(repository.isSupportedObjectType("FARMER")).thenReturn(true);
        when(repository.coordinateBoundaryState(anyString(), any(), any()))
                .thenReturn(Optional.of(FormalSamplePointRepository.BoundaryContainment.INSIDE));
        when(repository.update(eq(id), eq(3L), any(), eq(subject), any())).thenReturn(Optional.of(point));
        var service = new FormalSamplePointService(repository, access, principals,
                mock(SamplePointCoordinateGuard.class), mock(BusinessAuditRecorder.class),
                new ObjectMapper(), Clock.systemUTC());
        var draft = new FormalSamplePointDraft("跨地区编辑", "231102", "样本地址",
                new BigDecimal("127.4"), new BigDecimal("50.2"), "FARMER", "other-owner", null);
        if (allowed) {
            assertThat(service.update(id, 3L, draft)).isSameAs(point);
            verify(repository).update(eq(id), eq(3L), any(), eq(subject), any());
        } else {
            assertThatThrownBy(() -> service.update(id, 3L, draft)).isInstanceOf(AccessDeniedException.class);
            verify(repository, never()).update(any(), anyLong(), any(), anyString(), any());
        }
    }
}
