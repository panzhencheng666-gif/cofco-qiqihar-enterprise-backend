package com.cofco.qiqihar.graintrade.marketintelligence;
import static org.assertj.core.api.Assertions.*;
import java.nio.file.Path;
import java.time.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IqsNewsSearchClientTest {
    @TempDir Path temporary;
    static final Instant NOW = Instant.parse("2026-09-28T15:00:00Z");
    static final Instant END = NOW.plusSeconds(600);
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    static final IqsSearchRequest.Credentials CREDS = new IqsSearchRequest.Credentials("dummy-id", "dummy-secret", "dummy-token", END);
    NewsSearchBudget budget(int count) throws Exception {
        var path = temporary.resolve("budget");
        NewsSearchBudget.initialize(path, END, count);
        return new NewsSearchBudget(path, END, count);
    }
    @Test void oneReservationAllowsOneSignedCallAndParsesCandidates() throws Exception {
        var calls = new AtomicInteger();
        var client = new IqsNewsSearchClient(() -> CREDS, budget(1), CLOCK, request -> {
            calls.incrementAndGet();
            assertThat(request.headers().firstValue("Authorization")).isPresent();
            return new IqsNewsSearchClient.Response(200, "{\"pageItems\":[{\"title\":\"Wheat\",\"link\":\"https://news.example/a\"}]}".getBytes());
        });
        assertThat(client.search("CNLiteBasic", "grain").state()).isEqualTo(NewsSearchResults.State.CANDIDATES);
        assertThat(client.search("CNLiteBasic", "grain").reason()).isEqualTo("BUDGET_CLOSED");
        assertThat(calls).hasValue(1);
    }
    @Test void exhaustedBudgetDoesNotEvenLoadCredentials() throws Exception {
        var loads = new AtomicInteger();
        var client = new IqsNewsSearchClient(() -> { loads.incrementAndGet(); return CREDS; },
            budget(0), CLOCK, request -> { throw new AssertionError("must not send"); });
        assertThat(client.search("GlobalAdvanced", "grain").reason()).isEqualTo("BUDGET_CLOSED");
        assertThat(loads).hasValue(0);
    }
    @Test void credentialsFailureIsSanitizedAndReservationIsNotRefunded() throws Exception {
        var client = new IqsNewsSearchClient(() -> { throw new java.io.IOException("secret"); },
            budget(1), CLOCK, request -> { throw new AssertionError("must not send"); });
        assertThat(client.search("GlobalAdvanced", "grain").reason()).isEqualTo("SEARCH_UNAVAILABLE");
        assertThat(client.search("GlobalAdvanced", "grain").reason()).isEqualTo("BUDGET_CLOSED");
    }
    @Test void provider429IsReturnedWithoutApplicationRetry() throws Exception {
        var calls = new AtomicInteger();
        var client = new IqsNewsSearchClient(() -> CREDS, budget(2), CLOCK, request -> {
            calls.incrementAndGet(); return new IqsNewsSearchClient.Response(429, new byte[0]);
        });
        assertThat(client.search("CNLiteBasic", "grain").reason()).isEqualTo("HTTP_429");
        assertThat(calls).hasValue(1);
    }
    @Test void invalidQueryDoesNotConsumeReservation() throws Exception {
        var calls = new AtomicInteger();
        var client = new IqsNewsSearchClient(() -> CREDS, budget(1), CLOCK, request -> {
            calls.incrementAndGet(); return new IqsNewsSearchClient.Response(200, "{\"pageItems\":[]}".getBytes());
        });
        assertThat(client.search("CNLiteBasic", "").reason()).isEqualTo("INVALID_REQUEST");
        assertThat(client.search("CNLiteBasic", "grain").state()).isEqualTo(NewsSearchResults.State.EMPTY);
        assertThat(calls).hasValue(1);
    }
    @Test void realTransportDoesNotFollowRedirectOrRetry429() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var calls = new AtomicInteger();
        server.createContext("/redirect", exchange -> {
            calls.incrementAndGet();
            exchange.getResponseHeaders().set("Location", "/limited");
            exchange.sendResponseHeaders(302, -1); exchange.close();
        });
        server.createContext("/limited", exchange -> {
            calls.incrementAndGet(); exchange.sendResponseHeaders(429, -1); exchange.close();
        });
        server.start();
        try {
            var base = "http://127.0.0.1:" + server.getAddress().getPort();
            assertThat(IqsNewsSearchClient.send(java.net.http.HttpRequest.newBuilder(
                java.net.URI.create(base + "/redirect")).POST(java.net.http.HttpRequest.BodyPublishers.noBody()).build()).status()).isEqualTo(302);
            assertThat(calls).hasValue(1);
            assertThat(IqsNewsSearchClient.send(java.net.http.HttpRequest.newBuilder(
                java.net.URI.create(base + "/limited")).POST(java.net.http.HttpRequest.BodyPublishers.noBody()).build()).status()).isEqualTo(429);
            assertThat(calls).hasValue(2);
        } finally { server.stop(0); }
    }
    @Test void realTransportRejectsOversizedBody() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                var bytes = new byte[2_000_001];
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } finally { exchange.close(); }
        });
        server.start();
        try {
            var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/")).build();
            assertThatThrownBy(() -> IqsNewsSearchClient.send(request))
                .isInstanceOf(java.io.IOException.class).hasMessage("Search transport unavailable").hasNoCause();
        } finally { server.stop(0); }
    }
    @Test void deadlineIsRecheckedAfterCredentialLoading() throws Exception {
        var time = new java.util.concurrent.atomic.AtomicReference<>(NOW);
        var clock = new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return time.get(); }
        };
        var client = new IqsNewsSearchClient(() -> { time.set(END); return CREDS; },
            budget(1), clock, request -> { throw new AssertionError("must not send"); });
        assertThat(client.search("GlobalAdvanced", "grain").reason()).isEqualTo("BUDGET_CLOSED");
    }
}
