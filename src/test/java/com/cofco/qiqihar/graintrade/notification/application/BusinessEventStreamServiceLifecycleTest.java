package com.cofco.qiqihar.graintrade.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cofco.qiqihar.graintrade.shared.security.application.AccessControl;
import com.cofco.qiqihar.graintrade.shared.security.application.AuthorizedReadScope;
import com.cofco.qiqihar.graintrade.shared.security.domain.SecurityPrincipal;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BusinessEventStreamServiceLifecycleTest {

    @Test
    void stoppingApplicationEndsActiveStreamsBeforeWebServerShutdown() throws Exception {
        BusinessNotificationRepository repository = mock(BusinessNotificationRepository.class);
        AccessControl accessControl = mock(AccessControl.class);
        SecurityPrincipal principal = new SecurityPrincipal(
                "employee-1", "员工一", "UNIT", Set.of("BUSINESS_READ"), Set.of("230200"));
        AuthorizedReadScope scope = new AuthorizedReadScope("employee-1", Set.of("230200"));
        CountDownLatch firstQuery = new CountDownLatch(1);
        AtomicInteger queryCount = new AtomicInteger();
        when(accessControl.requireAuthenticated()).thenReturn(principal);
        when(accessControl.requireReadScope()).thenReturn(scope);
        when(repository.findVisibleAfter(scope, principal.subjectId(), 0, 100))
                .thenAnswer(ignored -> {
                    queryCount.incrementAndGet();
                    firstQuery.countDown();
                    return List.of();
                });

        BusinessEventStreamService service = new BusinessEventStreamService(repository, accessControl);
        service.start();
        service.stream(0);
        assertThat(firstQuery.await(2, TimeUnit.SECONDS)).isTrue();

        service.stop();
        int queriesAtStop = queryCount.get();
        Thread.sleep(Duration.ofMillis(1200));

        assertThat(service.isRunning()).isFalse();
        assertThat(queryCount).hasValue(queriesAtStop);
    }
}
