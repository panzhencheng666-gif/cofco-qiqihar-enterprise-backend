package com.cofco.qiqihar.graintrade.overview.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import com.cofco.qiqihar.graintrade.shared.security.application.AccessControl;
import com.cofco.qiqihar.graintrade.shared.application.AuthenticationRequiredException;
import org.junit.jupiter.api.Test;

class OverviewMapRevisionServiceTest {
    @Test
    void requiresSharedMapAuthorizationBeforeReadingTheOpaqueRevision() {
        var access = mock(AccessControl.class);
        var repository = mock(OverviewMapRevisionRepository.class);
        when(repository.currentRevision()).thenReturn("revision");
        assertThat(new OverviewMapRevisionService(access,repository).currentRevision()).isEqualTo("revision");
        var order = inOrder(access,repository);
        order.verify(access).requireOverviewReadScope();
        order.verify(repository).currentRevision();
    }
    @Test
    void unauthenticatedRequestsNeverReadTheRevision() {
        var access = mock(AccessControl.class);
        var repository = mock(OverviewMapRevisionRepository.class);
        when(access.requireOverviewReadScope()).thenThrow(new AuthenticationRequiredException());
        assertThatThrownBy(() -> new OverviewMapRevisionService(access,repository).currentRevision())
                .isInstanceOf(AuthenticationRequiredException.class);
        verifyNoInteractions(repository);
    }
}
