package com.cofco.qiqihar.graintrade.marketintelligence;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.time.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;

class EcsNewsCredentialsTest {
    private HttpServer server;
    private URI base;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-28T15:00:00Z"), ZoneOffset.UTC);
    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/latest");
    }
    @AfterEach void stop() { server.stop(0); }
    private EcsNewsCredentials provider() { return new EcsNewsCredentials(base, "NewsRole", CLOCK, Duration.ofSeconds(2)); }
    private void token(String value) {
        server.createContext("/latest/api/token", e -> {
            byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            e.sendResponseHeaders(200, bytes.length); e.getResponseBody().write(bytes); e.close();
        });
    }
    @Test void getsV2TokenThenOnlyConfiguredRole() throws Exception {
        var method = new AtomicReference<String>();
        var ttl = new AtomicReference<String>();
        var receivedToken = new AtomicReference<String>();
        server.createContext("/latest/api/token", e -> {
            method.set(e.getRequestMethod()); ttl.set(e.getRequestHeaders().getFirst("X-aliyun-ecs-metadata-token-ttl-seconds"));
            e.sendResponseHeaders(200, 5); e.getResponseBody().write("token".getBytes()); e.close();
        });
        server.createContext("/latest/meta-data/ram/security-credentials/NewsRole", e -> {
            receivedToken.set(e.getRequestHeaders().getFirst("X-aliyun-ecs-metadata-token"));
            byte[] bytes = "{\"Code\":\"Success\",\"AccessKeyId\":\"dummy-id\",\"AccessKeySecret\":\"dummy-secret\",\"SecurityToken\":\"dummy-sts\",\"Expiration\":\"2026-09-28T16:00:00Z\"}".getBytes();
            e.sendResponseHeaders(200, bytes.length); e.getResponseBody().write(bytes); e.close();
        });
        var credentials = provider().load();
        assertThat(credentials).isNotNull();
        assertThat(credentials.id()).isEqualTo("dummy-id");
        assertThat(method.get()).isEqualTo("PUT");
        assertThat(ttl.get()).isEqualTo("180");
        assertThat(receivedToken.get()).isEqualTo("token");
    }
    @Test void redirectsDoNotReceiveMetadataToken() {
        token("token");
        var leaked = new AtomicInteger();
        server.createContext("/latest/meta-data/ram/security-credentials/NewsRole", e -> {
            e.getResponseHeaders().set("Location", base.resolve("/leak").toString());
            e.sendResponseHeaders(302, -1); e.close();
        });
        server.createContext("/leak", e -> { leaked.incrementAndGet(); e.close(); });
        assertThatThrownBy(() -> provider().load()).isInstanceOf(java.io.IOException.class).hasMessage("Metadata request failed");
        assertThat(leaked).hasValue(0);
    }
    @Test void malformedSecretResponseIsNotIncludedInException() {
        token("token");
        server.createContext("/latest/meta-data/ram/security-credentials/NewsRole", e -> {
            byte[] bytes = "PRIVATE-SECRET-INVALID-JSON".getBytes();
            e.sendResponseHeaders(200, bytes.length); e.getResponseBody().write(bytes); e.close();
        });
        assertThatThrownBy(() -> provider().load()).isInstanceOf(java.io.IOException.class)
            .hasMessage("Invalid metadata credentials").hasNoCause();
    }
    @Test void rejectsOversizedToken() {
        token("a".repeat(32769));
        assertThatThrownBy(() -> provider().load()).isInstanceOf(java.io.IOException.class).hasMessage("Metadata request failed");
    }
    @Test void rejectsArbitraryEndpointAndRolePathInjection() {
        assertThatThrownBy(() -> new EcsNewsCredentials(URI.create("https://example.com/latest"), "NewsRole", CLOCK, Duration.ofSeconds(1)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EcsNewsCredentials(base, "../other", CLOCK, Duration.ofSeconds(1)))
            .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsExpiredCredentials() {
        token("token");
        server.createContext("/latest/meta-data/ram/security-credentials/NewsRole", e -> {
            byte[] bytes = "{\"Code\":\"Success\",\"AccessKeyId\":\"dummy-id\",\"AccessKeySecret\":\"dummy-secret\",\"SecurityToken\":\"dummy-sts\",\"Expiration\":\"2026-09-28T15:00:00Z\"}".getBytes();
            e.sendResponseHeaders(200, bytes.length); e.getResponseBody().write(bytes); e.close();
        });
        assertThatThrownBy(() -> provider().load()).isInstanceOf(java.io.IOException.class)
            .hasMessage("Invalid metadata credentials").hasNoCause();
    }
    @Test void stalledTokenBodyHasBoundedDeadline() {
        var release = new java.util.concurrent.CountDownLatch(1);
        server.createContext("/latest/api/token", e -> {
            e.sendResponseHeaders(200, 0); e.getResponseBody().write('t'); e.getResponseBody().flush();
            try { release.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { e.close(); }
        });
        try {
            var shortProvider = new EcsNewsCredentials(base, "NewsRole", CLOCK, Duration.ofMillis(250));
            assertTimeoutPreemptively(Duration.ofSeconds(3), () ->
                assertThatThrownBy(shortProvider::load).isInstanceOf(java.io.IOException.class).hasMessage("Metadata request failed"));
        } finally { release.countDown(); }
    }
}
