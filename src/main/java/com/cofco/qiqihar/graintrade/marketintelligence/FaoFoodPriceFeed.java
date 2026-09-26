package com.cofco.qiqihar.graintrade.marketintelligence;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Autonomous ingestion of the FAO's published monthly food price indices. */
@Component
public class FaoFoodPriceFeed {
    public static final String SOURCE_URL = "https://www.fao.org/media/docs/worldfoodsituationlibraries/default-document-library/food_price_indices_data.csv?download=true";
    private static final String CODE = "fao-food-price-index";
    private static final int MAX_BYTES = 500_000;
    private static final Logger LOG = LoggerFactory.getLogger(FaoFoodPriceFeed.class);
    private final JdbcClient jdbc;
    private final JdbcTemplate template;
    private final TransactionTemplate transactions;
    private final URI uri;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    public FaoFoodPriceFeed(JdbcClient jdbc, JdbcTemplate template, PlatformTransactionManager transactionManager,
            @Value("${qiqihar.market-intelligence.fao-food-price.url:" + SOURCE_URL + "}") String sourceUrl) {
        this.jdbc = jdbc;
        this.template = template;
        this.transactions = new TransactionTemplate(transactionManager);
        this.uri = URI.create(sourceUrl);
        if (!"https".equals(uri.getScheme()) || !"www.fao.org".equals(uri.getHost()))
            throw new IllegalArgumentException("FAO food-price source must use official HTTPS host");
    }

    public record Point(LocalDate period, BigDecimal value, String sourceUrl) { }
    public record State(Instant lastAttemptAt, Instant lastSuccessAt, LocalDate latestPeriod, String lastError) { }

    public void refresh() {
        var attemptedAt = Instant.now();
        try {
            var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30))
                    .header("Accept", "text/csv")
                    .header("User-Agent", "QiLiang-MarketIntelligence/1.0 (+official index monitor)")
                    .GET().build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200 || !"www.fao.org".equals(response.uri().getHost())) {
                response.body().close();
                throw new IOException("FAO food-price HTTP " + response.statusCode());
            }
            byte[] bytes;
            try (var stream = response.body()) { bytes = stream.readNBytes(MAX_BYTES + 1); }
            if (bytes.length > MAX_BYTES) throw new IOException("FAO food-price CSV too large");
            var parsed = FaoFoodPriceCsv.parse(bytes, LocalDate.now(ZoneOffset.UTC));
            var sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            transactions.executeWithoutResult(status -> save(parsed, sha256, Instant.now()));
            LOG.info("FAO food-price index sync complete: observations={}, latest={}",
                    parsed.observations().size(), parsed.latestPeriod());
        } catch (Exception error) {
            LOG.warn("FAO food-price index sync failed: {}", error.toString());
            try { failure(attemptedAt, error.getClass().getSimpleName()); }
            catch (Exception stateError) { LOG.warn("Could not record FAO index failure: {}", stateError.toString()); }
        }
    }

    private void save(FaoFoodPriceCsv.Parsed parsed, String sha256, Instant fetchedAt) {
        var earliest = parsed.latestPeriod().minusMonths(71);
        var observations = parsed.observations().stream()
                .filter(row -> !row.period().isBefore(earliest)).toList();
        template.batchUpdate("""
                INSERT INTO market_intelligence.fao_food_price_index
                    (series_code,period,value,source_url,fetched_at,source_sha256)
                VALUES (?,?,?,?,?,?)
                ON CONFLICT (series_code,period) DO UPDATE SET
                    value=EXCLUDED.value,source_url=EXCLUDED.source_url,
                    fetched_at=EXCLUDED.fetched_at,source_sha256=EXCLUDED.source_sha256
                WHERE market_intelligence.fao_food_price_index.value IS DISTINCT FROM EXCLUDED.value
                   OR market_intelligence.fao_food_price_index.source_sha256 IS DISTINCT FROM EXCLUDED.source_sha256
                """, observations, 300, (statement, row) -> {
            statement.setString(1, row.series().code);
            statement.setObject(2, row.period());
            statement.setBigDecimal(3, row.value());
            statement.setString(4, uri.toString());
            statement.setObject(5, OffsetDateTime.ofInstant(fetchedAt, ZoneOffset.UTC));
            statement.setString(6, sha256);
        });
        jdbc.sql("""
                INSERT INTO market_intelligence.source_sync_state
                    (source_code,last_attempt_at,last_success_at,latest_period,last_error)
                VALUES (:code,:at,:at,:period,NULL)
                ON CONFLICT (source_code) DO UPDATE SET
                    last_attempt_at=:at,last_success_at=:at,latest_period=:period,last_error=NULL
                """).param("code", CODE).param("at", OffsetDateTime.ofInstant(fetchedAt, ZoneOffset.UTC))
                .param("period", parsed.latestPeriod()).update();
    }

    private void failure(Instant attemptedAt, String error) {
        jdbc.sql("""
                INSERT INTO market_intelligence.source_sync_state (source_code,last_attempt_at,last_error)
                VALUES (:code,:at,:error)
                ON CONFLICT (source_code) DO UPDATE SET last_attempt_at=:at,last_error=:error
                """).param("code", CODE).param("at", OffsetDateTime.ofInstant(attemptedAt, ZoneOffset.UTC))
                .param("error", error).update();
    }

    public List<Point> points(FaoFoodPriceSeries series, int months) {
        return jdbc.sql("""
                SELECT period,value,source_url FROM market_intelligence.fao_food_price_index
                WHERE series_code=:code ORDER BY period DESC LIMIT :months
                """).param("code", series.code).param("months", months)
                .query((rs, row) -> new Point(rs.getObject("period", LocalDate.class),
                        rs.getBigDecimal("value"), rs.getString("source_url")))
                .list().reversed();
    }

    public State state() {
        return jdbc.sql("""
                SELECT last_attempt_at,last_success_at,latest_period,last_error
                FROM market_intelligence.source_sync_state WHERE source_code=:code
                """).param("code", CODE).query((rs, row) -> new State(
                    instant(rs.getObject("last_attempt_at", OffsetDateTime.class)),
                    instant(rs.getObject("last_success_at", OffsetDateTime.class)),
                    rs.getObject("latest_period", LocalDate.class), rs.getString("last_error")))
                .optional().orElse(new State(null, null, null, null));
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    @Component
    @Profile("!test")
    @ConditionalOnProperty(name = "qiqihar.market-intelligence.fao-food-price.enabled", havingValue = "true", matchIfMissing = true)
    static class Refresh {
        private final FaoFoodPriceFeed feed;
        Refresh(FaoFoodPriceFeed feed) { this.feed = feed; }
        @Scheduled(initialDelayString = "${qiqihar.market-intelligence.fao-food-price.initial-delay:50s}",
                fixedDelayString = "${qiqihar.market-intelligence.fao-food-price.refresh-delay:6h}")
        public void run() { feed.refresh(); }
    }
}
