package com.cofco.qiqihar.graintrade.marketintelligence;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpClient;
import java.net.ProxySelector;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

final class IqsNewsSearchClient {
    interface CredentialsSource { IqsSearchRequest.Credentials load() throws IOException; }
    interface Sender { Response send(HttpRequest request) throws IOException; }
    record Response(int status, byte[] body) {}
    private final CredentialsSource source;
    private final NewsSearchBudget budget;
    private final Clock clock;
    private final Sender sender;

    IqsNewsSearchClient(EcsNewsCredentials source, NewsSearchBudget budget, Clock clock) {
        this(source::load, budget, clock, IqsNewsSearchClient::send);
    }

    IqsNewsSearchClient(CredentialsSource source, NewsSearchBudget budget, Clock clock, Sender sender) {
        this.source = Objects.requireNonNull(source);
        this.budget = Objects.requireNonNull(budget);
        this.clock = Objects.requireNonNull(clock);
        this.sender = Objects.requireNonNull(sender);
    }

    NewsSearchResults.Result search(String engine, String query) {
        try {
            IqsSearchRequest.payload(engine, query);
        } catch (IllegalArgumentException invalid) {
            return failed("INVALID_REQUEST");
        }
        try {
            if (!budget.reserve(engine, clock.instant())) return failed("BUDGET_CLOSED");
            var credentials = source.load();
            if (!budget.beforeDeadline(clock.instant())) return failed("BUDGET_CLOSED");
            var request = IqsSearchRequest.create(credentials, engine, query, clock.instant(),
                    UUID.randomUUID().toString());
            if (!budget.beforeDeadline(clock.instant())) return failed("BUDGET_CLOSED");
            var response = sender.send(request);
            return NewsSearchResults.parse(response.status(), response.body(), clock.instant());
        } catch (IOException | RuntimeException unavailable) {
            // No raw provider/credential exception reaches callers; reservations are never refunded.
            return failed("SEARCH_UNAVAILABLE");
        }
    }

    private static NewsSearchResults.Result failed(String reason) {
        return new NewsSearchResults.Result(NewsSearchResults.State.FAILED, List.of(), 0, 0, reason);
    }

    /** One bounded HTTP attempt; no redirects, proxy forwarding, or application retries. */
    static Response send(HttpRequest request) throws IOException {
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
                .proxy(ProxySelector.of(null)).followRedirects(HttpClient.Redirect.NEVER).build();
        var future = client.sendAsync(request, ignored -> new NewsSearchClient.LimitedBody());
        try {
            var response = future.get(20, TimeUnit.SECONDS);
            return new Response(response.statusCode(), response.body());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Search transport interrupted");
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) {
            throw new IOException("Search transport unavailable");
        } finally {
            future.cancel(true);
            client.shutdownNow();
        }
    }
}
