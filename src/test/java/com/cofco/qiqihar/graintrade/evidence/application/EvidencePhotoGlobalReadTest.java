package com.cofco.qiqihar.graintrade.evidence.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.cofco.qiqihar.graintrade.shared.security.application.*;
import com.cofco.qiqihar.graintrade.shared.security.domain.SecurityPrincipal;
import com.cofco.qiqihar.graintrade.shared.audit.application.BusinessAuditRecorder;
import com.cofco.qiqihar.graintrade.shared.application.AccessDeniedException;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class EvidencePhotoGlobalReadTest {
    @Test void attachedBusinessPhotoIsGlobalButUnattachedPhotosRemainOwnerOnly() {
        var access = mock(AccessControl.class);
        var repo = mock(EvidencePhotoRepository.class);
        var principal = new SecurityPrincipal("reader", "UNIT", Set.of("BUSINESS_READ"), Set.of());
        when(access.require("BUSINESS_READ", null)).thenReturn(principal);
        when(access.requireBusinessReadScope()).thenReturn(new AuthorizedReadScope("reader", Set.of("*")));
        var service = new EvidencePhotoService(repo, access, Clock.systemUTC(), null, mock(BusinessAuditRecorder.class));
        UUID id = UUID.randomUUID();
        when(repo.find(id)).thenReturn(Optional.of(photo(id, "ATTACHED", "other")));
        assertThat(service.content(id)).isNotNull();
        verify(access).requireBusinessReadScope();
        verify(access, never()).require("BUSINESS_READ", "230221990");
        when(repo.find(id)).thenReturn(Optional.of(photo(id, "STAGED", "other")));
        assertThatThrownBy(() -> service.content(id)).isInstanceOf(AccessDeniedException.class);
        when(repo.find(id)).thenReturn(Optional.of(photo(id, "STAGED", "reader")));
        assertThat(service.content(id)).isNotNull();
        verify(access, times(1)).requireBusinessReadScope();
        verify(repo, never()).attach(any(), any(), any(), any(), any());
    }
    private static EvidencePhotoRepository.StoredEvidencePhoto photo(UUID id, String state, String owner) {
        var view = new EvidencePhotoView(id, state, "proof.png", "image/png", 1, "hash", null,
                null, null, "watermark", owner, OffsetDateTime.now(), "PRODUCTION", "record");
        return new EvidencePhotoRepository.StoredEvidencePhoto(view, new byte[]{1}, new byte[]{1},
                "230221990", "DATABASE", null, "hash");
    }
}
