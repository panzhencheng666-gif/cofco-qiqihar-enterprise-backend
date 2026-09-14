package com.cofco.qiqihar.graintrade.regionalproduction.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.scheduling.annotation.Scheduled;

class RegionalRefreshResilienceTest {
    @Test
    void cancelsOversizedStreamingSourceBeforeEndOfBodyAndContinuesNextSource() throws Exception {
        var release = new CountDownLatch(1);
        var rejected = new CountDownLatch(1);
        var nextSource = new CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            server.setExecutor(executor);
            server.createContext("/large", exchange -> {
                try (exchange) {
                    exchange.sendResponseHeaders(200, 0);
                    byte[] chunk = new byte[1024 * 1024];
                    for (int i = 0; i < 21; i++) exchange.getResponseBody().write(chunk);
                    exchange.getResponseBody().flush();
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            server.createContext("/next", exchange -> {
                try (exchange) {
                    exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody().write("农业公开信息".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
            });
            server.start();
            var repository = mock(RegionalPublicDataRepository.class);
            var url = "http://127.0.0.1:" + server.getAddress().getPort();
            when(repository.due(any())).thenReturn(List.of(source("large", url + "/large"), source("next", url + "/next")));
            doAnswer(invocation -> { rejected.countDown(); return null; }).when(repository).recordFailure(eq("large"), any(), any());
            doAnswer(invocation -> { nextSource.countDown(); return null; }).when(repository).recordPageSuccess(eq("next"), any(), any(), any());
            var worker = new RegionalPublicDataRefreshWorker(repository, mock(RegionalSourceDiscovery.class));
            var result = executor.submit(worker::retryDueSources);
            try {
                assertThat(rejected.await(3, TimeUnit.SECONDS)).as("reject oversized data before the server ends its response").isTrue();
                assertThat(nextSource.await(2, TimeUnit.SECONDS)).as("continue the next source after rejection").isTrue();
            } finally {
                release.countDown();
                result.get(8, TimeUnit.SECONDS);
                server.stop(0);
            }
        } finally { server.stop(0); }
    }

    @Test
    void shutdownInterruptDoesNotBecomeSourceFailureOrContinueRequests() throws Exception {
        var repository = mock(RegionalPublicDataRepository.class);
        var discovery = mock(RegionalSourceDiscovery.class);
        when(repository.due(any())).thenReturn(List.of(source("first", "http://127.0.0.1:1/"), source("second", "http://127.0.0.1:1/")));
        doAnswer(invocation -> { Thread.currentThread().interrupt(); return null; }).when(discovery).discover(any());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            boolean interrupted = executor.submit(() -> {
                new RegionalPublicDataRefreshWorker(repository, discovery).retryDueSources();
                return Thread.currentThread().isInterrupted();
            }).get(5, TimeUnit.SECONDS);
            assertThat(interrupted).as("shutdown interrupt is preserved").isTrue();
            verify(repository, never()).recordFailure(any(), any(), any());
        }
    }

    @Test
    void slowRegionalCollectionDoesNotBlockOtherScheduledBusinessWork() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var discovery = mock(RegionalSourceDiscovery.class);
        doAnswer(invocation -> { entered.countDown(); release.await(5, TimeUnit.SECONDS); return null; }).when(discovery).discover(any());
        var probe = new BusinessProbe();
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                    "qiqihar.regional-public-data.initial-delay", "1ms", "qiqihar.regional-public-data.daily-cron", "-")));
            context.register(RegionalPublicDataRefreshConfiguration.class,
                    org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration.class);
            context.registerBean(RegionalSourceDiscovery.class, () -> discovery);
            context.registerBean(RegionalPublicDataRepository.class, () -> mock(RegionalPublicDataRepository.class));
            context.registerBean(RegionalEstimateBatchService.class, () -> mock(RegionalEstimateBatchService.class));
            context.registerBean(RegionalPublicDataRefreshWorker.class);
            context.registerBean(BusinessProbe.class, () -> probe);
            context.refresh();
            try {
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                probe.enabled.set(true);
                assertThat(probe.ticked.await(1, TimeUnit.SECONDS)).as("business scheduler remains available during a slow regional fetch").isTrue();
            } finally { release.countDown(); }
        }
    }

    private static RegionalPublicDataRepository.DueSource source(String id, String url) {
        return new RegionalPublicDataRepository.DueSource(id, "230200", "AGRICULTURE", id, url, "GENERIC_PAGE");
    }

    static class BusinessProbe {
        final AtomicBoolean enabled = new AtomicBoolean();
        final CountDownLatch ticked = new CountDownLatch(1);
        @Scheduled(fixedDelay = 20) public void tick() { if (enabled.get()) ticked.countDown(); }
    }
}
