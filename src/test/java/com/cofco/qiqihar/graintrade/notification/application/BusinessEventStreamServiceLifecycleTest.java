package com.cofco.qiqihar.graintrade.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cofco.qiqihar.graintrade.shared.security.application.AccessControl;
import com.cofco.qiqihar.graintrade.shared.security.application.AuthorizedReadScope;
import com.cofco.qiqihar.graintrade.shared.security.application.SecurityPrincipalRepository;
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
        SecurityPrincipalRepository principals = mock(SecurityPrincipalRepository.class);
        SecurityPrincipal principal = new SecurityPrincipal(
                "employee-1", "员工一", "UNIT", Set.of("BUSINESS_READ"), Set.of("230200"));
        AuthorizedReadScope scope = new AuthorizedReadScope("employee-1", Set.of("230200"));
        CountDownLatch firstQuery = new CountDownLatch(1);
        AtomicInteger queryCount = new AtomicInteger();
        when(accessControl.requireAuthenticated()).thenReturn(principal);
        when(accessControl.requireReadScope()).thenReturn(scope);
        when(principals.findEnabled(principal.subjectId())).thenReturn(java.util.Optional.of(principal));
        when(repository.findVisibleAfter(scope, principal.subjectId(), 0, 100))
                .thenAnswer(ignored -> {
                    queryCount.incrementAndGet();
                    firstQuery.countDown();
                    return List.of();
                });

        BusinessEventStreamService service = new BusinessEventStreamService(repository, accessControl, principals);
        service.start();
        service.stream(0);
        assertThat(firstQuery.await(2, TimeUnit.SECONDS)).isTrue();

        service.stop();
        int queriesAtStop = queryCount.get();
        Thread.sleep(Duration.ofMillis(1200));

        assertThat(service.isRunning()).isFalse();
        assertThat(queryCount).hasValue(queriesAtStop);
    }

    @Test
    void reloadsCurrentRegionAuthorizationBeforeEveryStreamPoll() throws Exception {
        BusinessNotificationRepository repository = mock(BusinessNotificationRepository.class);
        AccessControl accessControl = mock(AccessControl.class);
        SecurityPrincipalRepository principals = mock(SecurityPrincipalRepository.class);
        SecurityPrincipal original = new SecurityPrincipal(
                "employee-1", "员工一", "UNIT", Set.of("BUSINESS_READ"), Set.of("230200"));
        SecurityPrincipal narrowed = new SecurityPrincipal(
                "employee-1", "员工一", "UNIT", Set.of("BUSINESS_READ"), Set.of("230281"));
        AuthorizedReadScope originalScope = new AuthorizedReadScope("employee-1", Set.of("230200"));
        AuthorizedReadScope narrowedScope = new AuthorizedReadScope("employee-1", Set.of("230281"));
        CountDownLatch originalQuery = new CountDownLatch(1);
        CountDownLatch narrowedQuery = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<SecurityPrincipal> current =
                new java.util.concurrent.atomic.AtomicReference<>(original);
        when(accessControl.requireAuthenticated()).thenReturn(original);
        when(accessControl.requireReadScope()).thenReturn(originalScope);
        when(principals.findEnabled(original.subjectId()))
                .thenAnswer(ignored -> java.util.Optional.of(current.get()));
        when(repository.findVisibleAfter(originalScope, original.subjectId(), 0, 100))
                .thenAnswer(ignored -> {
                    originalQuery.countDown();
                    return List.of();
                });
        when(repository.findVisibleAfter(narrowedScope, original.subjectId(), 0, 100))
                .thenAnswer(ignored -> {
                    narrowedQuery.countDown();
                    return List.of();
                });

        BusinessEventStreamService service = new BusinessEventStreamService(repository, accessControl, principals);
        service.start();
        service.stream(0);
        assertThat(originalQuery.await(2, TimeUnit.SECONDS)).isTrue();
        current.set(narrowed);
        assertThat(narrowedQuery.await(3, TimeUnit.SECONDS)).isTrue();
        service.stop();
    }
}
