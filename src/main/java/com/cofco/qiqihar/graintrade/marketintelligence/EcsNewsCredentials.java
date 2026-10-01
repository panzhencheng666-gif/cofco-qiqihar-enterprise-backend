package com.cofco.qiqihar.graintrade.marketintelligence;
import java.net.URI;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import tools.jackson.databind.json.JsonMapper;

/** IMDSv2 only, no credential persistence or logging. */
final class EcsNewsCredentials {
    private static final URI ECS = URI.create("http://100.100.100.200/latest");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final URI base;
    private final String role;
    private final Clock clock;
    private final Duration timeout;

    EcsNewsCredentials(String role, Clock clock) { this(ECS, role, clock, Duration.ofSeconds(4)); }

    // Loopback is accepted for isolated transport tests, never a configurable public destination.
    EcsNewsCredentials(URI base, String role, Clock clock, Duration timeout) {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(timeout, "timeout");
        boolean loopback = "http".equals(base.getScheme()) && "127.0.0.1".equals(base.getHost())
            && "/latest".equals(base.getPath()) && base.getUserInfo() == null
            && base.getQuery() == null && base.getFragment() == null;
        if ((!base.equals(ECS) && !loopback) || role == null || !role.matches("[A-Za-z0-9_-]{1,64}"))
            throw new IllegalArgumentException("Invalid metadata endpoint or role");
        if (timeout.compareTo(Duration.ofMillis(1)) < 0 || timeout.compareTo(Duration.ofSeconds(4)) > 0)
            throw new IllegalArgumentException("Invalid metadata timeout");
        this.base = base;
        this.role = role;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.timeout = timeout;
    }

    IqsSearchRequest.Credentials load() throws IOException {
        var client = HttpClient.newBuilder().connectTimeout(timeout).proxy(ProxySelector.of(null))
            .followRedirects(HttpClient.Redirect.NEVER).build();
        try {
            var tokenRequest = HttpRequest.newBuilder(URI.create(base + "/api/token"))
                .timeout(timeout).header("X-aliyun-ecs-metadata-token-ttl-seconds", "180")
                .PUT(HttpRequest.BodyPublishers.noBody()).build();
            var token = new String(fetch(client, tokenRequest), StandardCharsets.UTF_8);
            if (token.isEmpty() || !token.chars().allMatch(c -> c >= 33 && c <= 126))
                throw new IOException("Invalid metadata token");
            var request = HttpRequest.newBuilder(URI.create(base + "/meta-data/ram/security-credentials/" + role))
                .timeout(timeout).header("X-aliyun-ecs-metadata-token", token).GET().build();
            byte[] body = fetch(client, request);
            try {
                var root = JSON.readTree(body);
                if (root == null || !root.isObject() || !"Success".equals(root.path("Code").asString()))
                    throw new IllegalArgumentException();
                for (var key : new String[]{"AccessKeyId", "AccessKeySecret", "SecurityToken", "Expiration"})
                    if (!root.path(key).isString()) throw new IllegalArgumentException();
                var expires = Instant.parse(root.path("Expiration").asString());
                if (!expires.isAfter(clock.instant().plusSeconds(60))) throw new IllegalArgumentException();
                return new IqsSearchRequest.Credentials(root.path("AccessKeyId").asString(),
                    root.path("AccessKeySecret").asString(), root.path("SecurityToken").asString(), expires);
            } catch (RuntimeException invalid) {
                // Jackson exception messages can contain raw credential response fragments.
                throw new IOException("Invalid metadata credentials");
            }
        } finally {
            client.shutdownNow();
        }
    }

    private byte[] fetch(HttpClient client, HttpRequest request) throws IOException {
        CompletableFuture<HttpResponse<byte[]>> pending = client.sendAsync(request, ignored -> new NewsSearchClient.LimitedBody(32768));
        try {
            var response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (response.statusCode() != 200) throw new IOException("Metadata request failed");
            return response.body();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Metadata request interrupted");
        } catch (ExecutionException | TimeoutException failure) {
            throw new IOException("Metadata request failed");
        } finally {
            if (!pending.isDone()) pending.cancel(true);
        }
    }
}
