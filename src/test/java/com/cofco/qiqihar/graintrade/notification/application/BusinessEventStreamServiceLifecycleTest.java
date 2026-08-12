package com.cofco.qiqihar.graintrade.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;

import com.cofco.qiqihar.graintrade.shared.security.application.AccessControl;
import com.cofco.qiqihar.graintrade.shared.security.application.AuthorizedReadScope;
import com.cofco.qiqihar.graintrade.shared.security.application.SecurityPrincipalRepository;
import com.cofco.qiqihar.graintrade.shared.security.domain.SecurityPrincipal;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BusinessEventStreamServiceLifecycleTest {

    @Test
    void stoppingApplicationEndsActiveStreamsBeforeWebServerShutdown() throws Exception {
        BusinessEventDeliveryService deliveries = mock(BusinessEventDeliveryService.class);
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
        when(deliveries.drain(streamConsumer(principal.subjectId()), anyString(), eq(scope),
                eq(principal.subjectId()), eq(0L), eq(100), any()))
                .thenAnswer(ignored -> {
                    queryCount.incrementAndGet();
                    firstQuery.countDown();
                    return emptyDrain();
                });

        BusinessEventStreamService service = new BusinessEventStreamService(deliveries, accessControl, principals);
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
        BusinessEventDeliveryService deliveries = mock(BusinessEventDeliveryService.class);
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
        when(deliveries.drain(streamConsumer(original.subjectId()), anyString(), eq(originalScope),
                eq(original.subjectId()), eq(0L), eq(100), any()))
                .thenAnswer(ignored -> {
                    originalQuery.countDown();
                    return emptyDrain();
                });
        when(deliveries.drain(streamConsumer(original.subjectId()), anyString(), eq(narrowedScope),
                eq(original.subjectId()), eq(0L), eq(100), any()))
                .thenAnswer(ignored -> {
                    narrowedQuery.countDown();
                    return emptyDrain();
                });

        BusinessEventStreamService service = new BusinessEventStreamService(deliveries, accessControl, principals);
        service.start();
        service.stream(0);
        assertThat(originalQuery.await(2, TimeUnit.SECONDS)).isTrue();
        current.set(narrowed);
        assertThat(narrowedQuery.await(3, TimeUnit.SECONDS)).isTrue();
        service.stop();
    }

    @Test
    void assignsAnIndependentDurableConsumerToConnectionsOnSeparateServiceInstances() throws Exception {
        BusinessEventDeliveryService deliveries = mock(BusinessEventDeliveryService.class);
        AccessControl accessControl = mock(AccessControl.class);
        SecurityPrincipalRepository principals = mock(SecurityPrincipalRepository.class);
        SecurityPrincipal principal = new SecurityPrincipal(
                "employee-1", "员工一", "UNIT", Set.of("BUSINESS_READ"), Set.of("230200"));
        AuthorizedReadScope scope = new AuthorizedReadScope("employee-1", Set.of("230200"));
        CountDownLatch connectionsStarted = new CountDownLatch(2);
        Set<String> consumerIds = ConcurrentHashMap.newKeySet();
        when(accessControl.requireAuthenticated()).thenReturn(principal);
        when(accessControl.requireReadScope()).thenReturn(scope);
        when(principals.findEnabled(principal.subjectId())).thenReturn(java.util.Optional.of(principal));
        when(deliveries.drain(anyString(), anyString(), eq(scope), eq(principal.subjectId()),
                eq(0L), eq(100), any()))
                .thenAnswer(invocation -> {
                    consumerIds.add(invocation.getArgument(0, String.class));
                    connectionsStarted.countDown();
                    return emptyDrain();
                });

        BusinessEventStreamService firstService =
                new BusinessEventStreamService(deliveries, accessControl, principals);
        BusinessEventStreamService secondService =
                new BusinessEventStreamService(deliveries, accessControl, principals);
        firstService.start();
        secondService.start();
        try {
            firstService.stream(0);
            secondService.stream(0);
            assertThat(connectionsStarted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(consumerIds)
                    .hasSize(2)
                    .allMatch(consumerId -> consumerId.startsWith("sse:" + principal.subjectId() + ":"));
        } finally {
            firstService.stop();
            secondService.stop();
        }
    }

    private static BusinessEventDeliveryService.DrainResult emptyDrain() {
        return new BusinessEventDeliveryService.DrainResult(0, 0, 0, Duration.ZERO, false);
    }

    private static String streamConsumer(String subjectId) {
        return argThat(consumerId -> consumerId != null
                && consumerId.startsWith("sse:" + subjectId + ":"));
    }
}
