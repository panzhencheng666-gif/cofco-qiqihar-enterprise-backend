package com.cofco.qiqihar.graintrade.formalsamplepoint.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cofco.qiqihar.graintrade.samplepoint.coordinate.application.SamplePointCoordinateGuard;
import com.cofco.qiqihar.graintrade.shared.application.ConflictException;
import com.cofco.qiqihar.graintrade.shared.audit.application.BusinessAuditRecorder;
import com.cofco.qiqihar.graintrade.shared.security.application.AccessControl;
import com.cofco.qiqihar.graintrade.shared.security.application.SecurityPrincipalRepository;
import com.cofco.qiqihar.graintrade.shared.security.domain.SecurityPrincipal;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class FormalSamplePointVersionAuthorizationTest {
    @Test
    void rejectsFutureVersionEvenWhenAConcurrentReassignmentMakesThatVersionWritable() {
        UUID id = UUID.randomUUID();
        var repository = mock(FormalSamplePointRepository.class);
        var access = mock(AccessControl.class);
        var principals = mock(SecurityPrincipalRepository.class);
        var actor = new SecurityPrincipal("old-maintainer", "TEST",
                Set.of("BUSINESS_READ", "BUSINESS_CREATE"), Set.of("230202"));
        var current = mock(FormalSamplePointView.class);
        when(current.id()).thenReturn(id);
        when(current.version()).thenReturn(0L);
        when(current.regionCode()).thenReturn("230202");
        when(current.maintainerSubjectId()).thenReturn(actor.subjectId());
        when(repository.find(id)).thenReturn(Optional.of(current));
        when(access.require(anyString(), any())).thenReturn(actor);
        when(principals.findEnabled(actor.subjectId())).thenReturn(Optional.of(actor));
        when(repository.isSupportedObjectType("FARMER")).thenReturn(true);
        when(repository.coordinateBoundaryState(anyString(), any(), any()))
                .thenReturn(Optional.of(FormalSamplePointRepository.BoundaryContainment.INSIDE));
        // A concurrent authorized reassignment can advance the stored row to version 1.
        // Even if persistence would now accept 1, authorization was based on version 0.
        when(repository.update(any(), anyLong(), any(), anyString(), any()))
                .thenReturn(Optional.of(current));
        var service = new FormalSamplePointService(repository, access, principals,
                mock(SamplePointCoordinateGuard.class), mock(BusinessAuditRecorder.class),
                new ObjectMapper(), Clock.systemUTC());
        var draft = new FormalSamplePointDraft("样本", "230202", "地址",
                new BigDecimal("123.9"), new BigDecimal("47.3"), "FARMER", actor.subjectId(), null);
        assertThatThrownBy(() -> service.update(id, 1L, draft))
                .isInstanceOfSatisfying(ConflictException.class, error ->
                        org.assertj.core.api.Assertions.assertThat(error.code())
                                .isEqualTo("FORMAL_SAMPLE_POINT_VERSION_CONFLICT"));
    }
    @Test
    void administratorCannotReassignThroughOrdinaryUpdate() {
        var repository = mock(FormalSamplePointRepository.class);
        var access = mock(AccessControl.class);
        var principals = mock(SecurityPrincipalRepository.class);
        var actor = new SecurityPrincipal("admin", "TEST",
                Set.of("BUSINESS_READ", "BUSINESS_CREATE", "FORMAL_SAMPLE_MANAGE"), Set.of("230202"));
        var current = mock(FormalSamplePointView.class);
        UUID id = UUID.randomUUID();
        when(current.id()).thenReturn(id);
        when(current.version()).thenReturn(0L);
        when(current.regionCode()).thenReturn("230202");
        when(current.maintainerSubjectId()).thenReturn("owner");
        when(repository.find(id)).thenReturn(Optional.of(current));
        when(access.require(anyString(), any())).thenReturn(actor);
        when(principals.findEnabled("admin")).thenReturn(Optional.of(actor));
        when(repository.isSupportedObjectType("FARMER")).thenReturn(true);
        when(repository.coordinateBoundaryState(anyString(), any(), any()))
                .thenReturn(Optional.of(FormalSamplePointRepository.BoundaryContainment.INSIDE));
        when(repository.update(any(), anyLong(), any(), anyString(), any())).thenReturn(Optional.of(current));
        var service = new FormalSamplePointService(repository, access, principals,
                mock(SamplePointCoordinateGuard.class), mock(BusinessAuditRecorder.class),
                new ObjectMapper(), Clock.systemUTC());
        var draft = new FormalSamplePointDraft("样本", "230202", "地址",
                new BigDecimal("123.9"), new BigDecimal("47.3"), "FARMER", "admin", "岗位调整");
        assertThatThrownBy(() -> service.update(id, 0L, draft))
                .isInstanceOf(com.cofco.qiqihar.graintrade.shared.application.AccessDeniedException.class);
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never())
                .update(any(), anyLong(), any(), anyString(), any());
    }

}
