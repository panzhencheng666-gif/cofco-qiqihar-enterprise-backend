package com.cofco.qiqihar.graintrade.marketintelligence;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Calls one operator-configured search endpoint, never URLs obtained from search results. */
final class NewsSearchClient {
    private final URI endpoint;
    private final Duration timeout;

    NewsSearchClient(URI endpoint, Duration timeout) {
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(timeout, "timeout");
        boolean localHttp = "http".equals(endpoint.getScheme()) && "127.0.0.1".equals(endpoint.getHost());
        boolean https = "https".equals(endpoint.getScheme()) && endpoint.getHost() != null;
        if ((!localHttp && !https) || endpoint.getUserInfo() != null || endpoint.getFragment() != null
                || endpoint.getQuery() != null || !"/search".equals(endpoint.getPath())) {
            throw new IllegalArgumentException("Expected trusted HTTPS or loopback search endpoint without credentials/query");
        }
        if (timeout.compareTo(Duration.ofMillis(1)) < 0 || timeout.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("Search deadline must be between 1ms and 30s");
        }
        this.endpoint = endpoint;
        this.timeout = timeout;
    }

    NewsSearchResults.Result search(String query, String engine, Instant discoveredAt) {
        Objects.requireNonNull(discoveredAt, "discoveredAt");
        if (query == null || query.isBlank() || query.length() > 1000
                || engine == null || !engine.matches("[a-zA-Z0-9 _-]{1,80}")) {
            throw new IllegalArgumentException("Invalid news query or engine");
        }
        var uri = URI.create(endpoint + "?format=json&categories=news&q="
                + URLEncoder.encode(query, StandardCharsets.UTF_8) + "&engines="
                + URLEncoder.encode(engine, StandardCharsets.UTF_8));
        var client = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            var request = HttpRequest.newBuilder(uri).timeout(timeout)
                    .header("Accept", "application/json").header("User-Agent", "Qiliang-News-Discovery/1.0").GET().build();
            pending = client.sendAsync(request, ignored -> new LimitedBody());
            var response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return NewsSearchResults.parse(response.statusCode(), response.body(), discoveredAt);
        } catch (TimeoutException timeoutFailure) {
            return failed("TIMEOUT");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return failed("INTERRUPTED");
        } catch (ExecutionException failedRequest) {
            for (Throwable cause = failedRequest; cause != null; cause = cause.getCause()) {
                if (cause instanceof ResponseTooLarge) return failed("RESPONSE_TOO_LARGE");
                if (cause instanceof HttpTimeoutException) return failed("TIMEOUT");
            }
            return failed("TRANSPORT_ERROR");
        } finally {
            if (pending != null && !pending.isDone()) pending.cancel(true);
            client.shutdownNow();
        }
    }

    private static NewsSearchResults.Result failed(String reason) {
        return new NewsSearchResults.Result(NewsSearchResults.State.FAILED, List.of(), 0, 0, reason);
    }

    private static final class ResponseTooLarge extends RuntimeException {}

    /** Bound allocation before forwarding buffers, including chunked responses. */
    static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private int remaining;
        private boolean done;

        LimitedBody() { this(2_000_000); }
        LimitedBody(int limit) {
            if (limit <= 0) throw new IllegalArgumentException("Invalid body limit");
            remaining = limit;
        }

        @Override public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        @Override public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            delegate.onSubscribe(value);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            if (done) return;
            for (var buffer : buffers) {
                if (buffer.remaining() > remaining) {
                    done = true;
                    subscription.cancel();
                    delegate.onError(new ResponseTooLarge());
                    return;
                }
                remaining -= buffer.remaining();
            }
            delegate.onNext(buffers);
        }
        @Override public void onError(Throwable error) {
            if (!done) { done = true; delegate.onError(error); }
        }
        @Override public void onComplete() {
            if (!done) { done = true; delegate.onComplete(); }
        }
    }
}
