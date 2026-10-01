package com.cofco.qiqihar.graintrade.marketintelligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NewsSearchClientTest {
    private HttpServer server;
    private URI endpoint;
    private final Instant discovered = Instant.parse("2026-09-28T13:08:00Z");

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/search");
    }

    @AfterEach
    void stop() { server.stop(0); }

    @Test
    void sendsEncodedNewsQueryAndParsesMetadata() {
        var query = new AtomicReference<String>();
        server.createContext("/search", exchange -> {
            query.set(URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8));
            var bytes = "{\"results\":[{\"title\":\"秋粮收购\",\"url\":\"https://news.example/a\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        var result = new NewsSearchClient(endpoint, Duration.ofSeconds(2)).search("秋粮 & wheat", "bing news", discovered);
        assertThat(result.state()).isEqualTo(NewsSearchResults.State.CANDIDATES);
        assertThat(result.candidates()).hasSize(1);
        assertThat(query.get()).contains("q=秋粮 & wheat", "engines=bing news", "categories=news", "format=json");
    }

    @Test
    void neverFollowsRedirects() {
        var followed = new AtomicInteger();
        server.createContext("/search", exchange -> {
            exchange.getResponseHeaders().set("Location", endpoint.resolve("/private").toString());
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/private", exchange -> { followed.incrementAndGet(); exchange.close(); });
        assertThat(new NewsSearchClient(endpoint, Duration.ofSeconds(2)).search("grain", "bing news", discovered).reason())
            .isEqualTo("HTTP_302");
        assertThat(followed).hasValue(0);
    }

    @Test
    void rateLimitIsReturnedWithoutRetry() {
        var calls = new AtomicInteger();
        server.createContext("/search", exchange -> {
            calls.incrementAndGet();
            exchange.sendResponseHeaders(429, -1);
            exchange.close();
        });
        assertThat(new NewsSearchClient(endpoint, Duration.ofSeconds(2)).search("grain", "bing news", discovered).reason())
            .isEqualTo("HTTP_429");
        assertThat(calls).hasValue(1);
    }

    @Test
    void capsBodyEvenWithoutContentLength() {
        server.createContext("/search", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try { exchange.getResponseBody().write(new byte[2_000_001]); }
            finally { exchange.close(); }
        });
        assertThat(new NewsSearchClient(endpoint, Duration.ofSeconds(2)).search("grain", "bing news", discovered).reason())
            .isEqualTo("RESPONSE_TOO_LARGE");
    }

    @Test
    void deadlineIncludesStalledResponseBody() {
        var release = new CountDownLatch(1);
        var bodyStarted = new AtomicBoolean();
        server.createContext("/search", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write('{');
            exchange.getResponseBody().flush();
            bodyStarted.set(true);
            try { release.await(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        try {
            assertThat(new NewsSearchClient(endpoint, Duration.ofMillis(300)).search("grain", "bing news", discovered).reason())
                .isEqualTo("TIMEOUT");
            assertThat(bodyStarted).isTrue();
        } finally { release.countDown(); }
    }

    @Test
    void connectionFailureIsNotEmptySuccess() {
        server.stop(0);
        var result = new NewsSearchClient(endpoint, Duration.ofSeconds(2)).search("grain", "bing news", discovered);
        assertThat(result.state()).isEqualTo(NewsSearchResults.State.FAILED);
        assertThat(result.reason()).isEqualTo("TRANSPORT_ERROR");
    }

    @Test
    void callerInterruptionIsPreserved() {
        Thread.currentThread().interrupt();
        try {
            var result = new NewsSearchClient(endpoint, Duration.ofSeconds(2)).search("grain", "bing news", discovered);
            assertThat(result.reason()).isEqualTo("INTERRUPTED");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }

    @Test
    void rejectsUnsafeConfigurationAndInvalidQuery() {
        assertThatThrownBy(() -> new NewsSearchClient(URI.create("http://external.example/search"), Duration.ofSeconds(2)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NewsSearchClient(URI.create("https://user:pass@external.example/search"), Duration.ofSeconds(2)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NewsSearchClient(endpoint, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NewsSearchClient(endpoint, Duration.ofSeconds(2)).search("", "bing news", discovered))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
