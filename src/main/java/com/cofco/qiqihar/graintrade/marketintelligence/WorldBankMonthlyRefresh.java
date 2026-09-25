package com.cofco.qiqihar.graintrade.marketintelligence;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Downloads the official monthly workbook daily; freshness is based on its published month. */
@Component
@EnableScheduling
@Profile("!test")
@ConditionalOnProperty(name = "qiqihar.market-intelligence.world-bank.enabled", havingValue = "true", matchIfMissing = true)
public class WorldBankMonthlyRefresh {
    public static final String SOURCE_URL = "https://thedocs.worldbank.org/en/doc/74e8be41ceb20fa0da750cda2f6b9e4e-0050012026/related/CMO-Historical-Data-Monthly.xlsx";
    private static final int MAX_BYTES = 10_000_000;
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(WorldBankMonthlyRefresh.class);
    private final WorldBankMonthlyRepository repository;
    private final URI uri;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    public WorldBankMonthlyRefresh(WorldBankMonthlyRepository repository,
            @Value("${qiqihar.market-intelligence.world-bank.url:" + SOURCE_URL + "}") String sourceUrl) {
        this.repository = repository;
        this.uri = URI.create(sourceUrl);
        if (!"https".equals(uri.getScheme())) throw new IllegalArgumentException("World Bank source must use HTTPS");
    }

    @Scheduled(initialDelayString = "${qiqihar.market-intelligence.world-bank.initial-delay:20s}",
            fixedDelayString = "${qiqihar.market-intelligence.world-bank.refresh-delay:24h}")
    public void refresh() {
        var attemptedAt = Instant.now();
        try {
            var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30))
                    .header("Accept", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                    .GET().build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                response.body().close();
                throw new IOException("HTTP " + response.statusCode());
            }
            byte[] bytes;
            try (var stream = response.body()) { bytes = stream.readNBytes(MAX_BYTES + 1); }
            if (bytes.length > MAX_BYTES) throw new IOException("Source workbook too large");
            var parsed = WorldBankMonthlyWorkbook.parse(bytes);
            var sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            repository.save(parsed, uri.toString(), sha256, Instant.now());
            LOG.info("World Bank monthly benchmark sync complete: observations={}, published={}",
                    parsed.observations().size(), parsed.sourceUpdatedOn());
        } catch (Exception failure) {
            LOG.warn("World Bank monthly benchmark sync failed: {}", failure.toString());
            try { repository.failure(attemptedAt, failure.getClass().getSimpleName()); }
            catch (Exception stateFailure) { LOG.warn("Could not record source sync failure: {}", stateFailure.toString()); }
        }
    }
}
