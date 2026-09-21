package com.cofco.qiqihar.graintrade.regionalproduction.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RegionalPublicHttpTest {
    @Test
    void followsAtMostFiveValidatedPublicHttpsRedirects() throws Exception {
        var client = mock(HttpClient.class);
        var first = response(302, "/next", new byte[0]);
        var second = response(200, null, "农业正文".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        when(client.sendAsync(any(), anyByteHandler())).thenReturn(
                CompletableFuture.completedFuture(first), CompletableFuture.completedFuture(second));
        var request = HttpRequest.newBuilder(URI.create("https://public.example/start"))
                .timeout(Duration.ofSeconds(2)).header("User-Agent", "proof").build();

        var result = RegionalPublicHttp.sendFollowingPublicHttps(client, request, 1024, uri -> true);

        assertThat(result.body()).isEqualTo("农业正文".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var requests = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(client,times(2)).sendAsync(requests.capture(),anyByteHandler());
        assertThat(requests.getAllValues().get(1).uri()).isEqualTo(URI.create("https://public.example/next"));
        assertThat(requests.getAllValues().get(1).headers().firstValue("User-Agent")).contains("proof");
    }

    @Test
    void rejectsUnsafeMissingAndCyclicRedirectTargets() {
        assertThatThrownBy(() -> RegionalPublicHttp.redirectTarget(URI.create("https://public.example/start"),
                302, HttpHeaders.of(Map.of("location",List.of("http://public.example/next")),(a,b)->true),Set.of(),uri -> true))
                .hasMessageContaining("HTTPS");
        assertThatThrownBy(() -> RegionalPublicHttp.redirectTarget(URI.create("https://public.example/start"),
                302, HttpHeaders.of(Map.of(),(a,b)->true),Set.of(),uri -> true)).hasMessageContaining("Location");
        assertThatThrownBy(() -> RegionalPublicHttp.redirectTarget(URI.create("https://public.example/start"),
                302, HttpHeaders.of(Map.of("location",List.of("https://public.example/start")),(a,b)->true),
                Set.of(URI.create("https://public.example/start")),uri -> true)).hasMessageContaining("循环");
        assertThatThrownBy(() -> RegionalPublicHttp.redirectTarget(URI.create("https://public.example/start"),
                302, HttpHeaders.of(Map.of("location",List.of("https://127.0.0.1/secret")),(a,b)->true),Set.of(),uri -> false))
                .hasMessageContaining("公网");
    }

    @Test
    void rejectsASixthRedirect() {
        var client = mock(HttpClient.class);
        java.util.List<CompletableFuture<HttpResponse<byte[]>>> responses = new java.util.ArrayList<>();
        for (int i=1;i<=6;i++) responses.add(CompletableFuture.completedFuture(
                response(302,"https://public.example/step-"+i,new byte[0])));
        when(client.sendAsync(any(),anyByteHandler())).thenReturn(responses.get(0),responses.subList(1,responses.size()).toArray(CompletableFuture[]::new));
        var request=HttpRequest.newBuilder(URI.create("https://public.example/start")).timeout(Duration.ofSeconds(2)).build();
        assertThatThrownBy(() -> RegionalPublicHttp.sendFollowingPublicHttps(client,request,1024,uri -> true))
                .hasMessageContaining("超过5跳");
    }

    @Test
    void rejectsChallengeAndHomepageRedirectsInsteadOfTreatingThemAsContent() {
        assertThatThrownBy(() -> RegionalPublicHttp.requireUsableRedirectResult(
                URI.create("https://public.example/article/1"),
                URI.create("https://public.example/HumanMachineVerification.shtml?url=x"),
                "请完成人机验证".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .hasMessageContaining("访问验证");
        assertThatThrownBy(() -> RegionalPublicHttp.requireUsableRedirectResult(
                URI.create("https://public.example/article/1"), URI.create("https://public.example/"),
                "站点首页".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .hasMessageContaining("站点首页");
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse.BodyHandler<byte[]> anyByteHandler() {
        return any(HttpResponse.BodyHandler.class);
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<byte[]> response(int status, String location, byte[] body) {
        var response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.headers()).thenReturn(HttpHeaders.of(location == null ? Map.of() : Map.of("location",List.of(location)),(a,b)->true));
        when(response.body()).thenReturn(body);
        return response;
    }
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
