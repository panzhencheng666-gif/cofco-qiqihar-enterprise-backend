package com.cofco.qiqihar.graintrade.formalsamplepoint.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cofco.qiqihar.graintrade.samplepoint.coordinate.application.SamplePointCoordinateGuard;
import com.cofco.qiqihar.graintrade.shared.application.AccessDeniedException;
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
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class FormalSamplePointResponsibilityTest {
    private static final String REGION = "230221101";
    private final UUID id = UUID.randomUUID();
    private final FormalSamplePointRepository repository = mock(FormalSamplePointRepository.class);
    private final SecurityPrincipalRepository principals = mock(SecurityPrincipalRepository.class);
    private final FormalSamplePointView point = mock(FormalSamplePointView.class);

    private FormalSamplePointService service(String subject, Set<String> regions) {
        var actor = new SecurityPrincipal(subject, "TEST",
                Set.of("BUSINESS_READ", "BUSINESS_CREATE"), regions);
        when(principals.findEnabled(subject)).thenReturn(Optional.of(actor));
        when(principals.responsibleSubject(REGION, false)).thenReturn(Optional.of("new-owner"));
        when(point.id()).thenReturn(id);
        when(point.regionCode()).thenReturn(REGION);
        when(point.maintainerSubjectId()).thenReturn("old-owner");
        when(repository.find(id)).thenReturn(Optional.of(point));
        return new FormalSamplePointService(repository,
                new AccessControl(() -> Optional.of(subject), principals, true), principals,
                mock(SamplePointCoordinateGuard.class), mock(BusinessAuditRecorder.class),
                new ObjectMapper(), Clock.systemUTC());
    }

    @Test
    void newResponsibleOwnerCanUpdateSampleWithHistoricalMaintainer() {
        var service = service("new-owner", Set.of(REGION));
        when(repository.isSupportedObjectType("FARMER")).thenReturn(true);
        when(repository.coordinateBoundaryState(anyString(), any(), any()))
                .thenReturn(Optional.of(FormalSamplePointRepository.BoundaryContainment.INSIDE));
        when(repository.update(eq(id), eq(0L), any(), eq("new-owner"), any()))
                .thenReturn(Optional.of(point));
        service.update(id, 0L, new FormalSamplePointDraft("样本", REGION, "地址",
                new BigDecimal("123.9"), new BigDecimal("47.3"), "FARMER", "old-owner", null));
        var draft = ArgumentCaptor.forClass(FormalSamplePointDraft.class);
        verify(repository).update(eq(id), eq(0L), draft.capture(), eq("new-owner"), any());
        assertThat(draft.getValue().maintainerSubjectId()).isEqualTo("new-owner");
    }

    @Test
    void historicalMaintainerWithoutBindingCannotUpdateOrDelete() {
        var service = service("old-owner", Set.of());
        assertThatThrownBy(() -> service.update(id, 0L, null)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.delete(id, 0L)).isInstanceOf(AccessDeniedException.class);
        verify(repository, never()).update(any(), anyLong(), any(), anyString(), any());
        verify(repository, never()).delete(any(), anyLong(), anyString(), anyString());
    }

    @Test
    void ordinaryResponsibleOwnerCanDeleteWithoutAdministratorPermission() {
        var service = service("new-owner", Set.of(REGION));
        when(repository.delete(id, 0L, REGION, "new-owner"))
                .thenReturn(FormalSamplePointRepository.DeleteResult.DELETED);
        service.delete(id, 0L);
        verify(repository).delete(id, 0L, REGION, "new-owner");
    }

    @Test
    void ordinaryReaderCanBrowseSamplesOutsideAssignedRegion() {
        var service = service("reader", Set.of());
        assertThat(service.get(id)).isSameAs(point);
        service.list(REGION, null, 0, 20);
        verify(repository).findPage(REGION, null, 0, 20, Set.of("*"));
    }
}
