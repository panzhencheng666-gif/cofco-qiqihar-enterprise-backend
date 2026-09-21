package com.cofco.qiqihar.graintrade.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.cofco.qiqihar.graintrade.shared.security.application.AccessControl;
import com.cofco.qiqihar.graintrade.shared.security.application.AuthorizedReadScope;
import com.cofco.qiqihar.graintrade.shared.security.domain.SecurityPrincipal;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BusinessNotificationCursorTest {
    @Test void emptyScopedInboxStillStartsAtCurrentSharedStreamSequence() {
        var repository = mock(BusinessNotificationRepository.class);
        var access = mock(AccessControl.class);
        var scope = new AuthorizedReadScope("employee", Set.of("230221"));
        when(access.requireAuthenticated()).thenReturn(new SecurityPrincipal(
                "employee", "UNIT", Set.of("BUSINESS_READ"), Set.of("230221")));
        when(access.requireReadScope()).thenReturn(scope);
        when(repository.latestSequence()).thenReturn(9000L);
        when(repository.findVisible(scope, "employee", 50)).thenReturn(List.of());
        var result = new BusinessNotificationService(repository, access, Clock.systemUTC()).list();
        assertThat(result.items()).isEmpty();
        assertThat(result.currentSequence()).isEqualTo(9000L);
        var order = inOrder(repository);
        order.verify(repository).latestSequence();
        order.verify(repository).findVisible(scope, "employee", 50);
    }
}
