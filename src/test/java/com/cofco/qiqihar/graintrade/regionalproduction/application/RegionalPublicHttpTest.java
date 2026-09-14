package com.cofco.qiqihar.graintrade.regionalproduction.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class RegionalPublicHttpTest {
    @Test
    void timesOutWhileServerKeepsResponseBodyOpen() throws Exception {
        var release = new CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor(); var client = HttpClient.newHttpClient()) {
            server.setExecutor(executor);
            server.createContext("/", exchange -> {
                try (exchange) {
                    exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody().write(1);
                    exchange.getResponseBody().flush();
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            server.start();
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getAddress().getPort()))
                    .timeout(Duration.ofMillis(250)).build();
            try {
                long start = System.nanoTime();
                assertThatThrownBy(() -> RegionalPublicHttp.send(client, request, 1024)).isInstanceOf(HttpTimeoutException.class);
                assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
            } finally { release.countDown(); server.stop(0); }
        } finally { server.stop(0); }
    }

    @Test
    void acceptsBodyExactlyAtLimitAndRejectsTheNextByte() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (var client = HttpClient.newHttpClient()) {
            server.createContext("/", exchange -> {
                try (exchange) {
                    exchange.sendResponseHeaders(200, 4);
                    exchange.getResponseBody().write(new byte[] {1, 2, 3, 4});
                }
            });
            server.start();
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getAddress().getPort()))
                    .timeout(Duration.ofSeconds(2)).build();
            assertThat(RegionalPublicHttp.send(client, request, 4).body()).containsExactly(1, 2, 3, 4);
            assertThatThrownBy(() -> RegionalPublicHttp.send(client, request, 3))
                    .isInstanceOf(java.io.IOException.class).hasMessageContaining("超过读取上限");
        } finally { server.stop(0); }
    }
}
