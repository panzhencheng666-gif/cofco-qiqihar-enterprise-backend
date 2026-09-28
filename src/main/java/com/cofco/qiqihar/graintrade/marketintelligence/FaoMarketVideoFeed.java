package com.cofco.qiqihar.graintrade.marketintelligence;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Dated official FAO market-video links; playback stays on the publisher's page. */
@Component
public class FaoMarketVideoFeed {
    static final URI SOURCE = URI.create("https://www.fao.org/markets-and-trade/news-and-events/multimedia/video-detail/en");
    static final String CODE = "fao-market-video";
    private static final Logger LOG = LoggerFactory.getLogger(FaoMarketVideoFeed.class);
    private static final int MAX_BYTES = 1_000_000;
    private static final Pattern DATED_VIDEO = Pattern.compile(
            "<div class=\"d-list d-list-video\">(.*?)</div>\\s*</div>", Pattern.DOTALL);
    private static final Pattern TITLE_LINK = Pattern.compile(
            "<h5 class=\"title-link\">\\s*<a href=\"([^\"]+)\"[^>]*class=\"title-link\">([^<]+)</a>", Pattern.DOTALL);
    private static final Pattern DATE = Pattern.compile("<h6 class=\"date\">([0-9]{2}/[0-9]{2}/[0-9]{4})</h6>");
    private static final DateTimeFormatter SOURCE_DATE = DateTimeFormatter.ofPattern("dd/MM/uuuu");
    private final JdbcClient jdbc;
    private final JdbcTemplate template;
    private final TransactionTemplate transactions;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    public FaoMarketVideoFeed(JdbcClient jdbc, JdbcTemplate template,
                              PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.template = template;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public record Video(String title, String url, LocalDate publishedOn) { }

    static List<Video> parse(String html, Instant fetchedAt) throws IOException {
        var blocks = DATED_VIDEO.matcher(html);
        var result = new ArrayList<Video>();
        while (blocks.find() && result.size() < 40) {
            var link = TITLE_LINK.matcher(blocks.group(1));
            var date = DATE.matcher(blocks.group(1));
            if (!link.find() || !date.find()) continue;
            try {
                var publishedOn = LocalDate.parse(date.group(1), SOURCE_DATE);
                if (publishedOn.isAfter(fetchedAt.atZone(ZoneOffset.UTC).toLocalDate().plusDays(1))) continue;
                var url = URI.create(link.group(1));
                if (!"https".equals(url.getScheme())) continue;
                var officialPage = "www.fao.org".equals(url.getHost()) && url.getPath().startsWith(
                        "/markets-and-trade/news-and-events/multimedia/video-detail/");
                var publisherListedYouTube = "www.youtube.com".equals(url.getHost())
                        && "/watch".equals(url.getPath()) && url.getQuery() != null
                        && url.getQuery().matches("(?:^|.*&)v=[A-Za-z0-9_-]{11}(?:&.*|$)");
                if (!officialPage && !publisherListedYouTube) continue;
                var title = link.group(2).replace("&amp;", "&").replace("&#39;", "'")
                        .replace("&quot;", "\"").trim();
                if (title.isBlank() || title.length() > 500) continue;
                result.add(new Video(title, url.toString(), publishedOn));
            } catch (RuntimeException invalid) {
                // Skip malformed source entries, preserving only explicit source dates.
            }
        }
        if (result.isEmpty()) throw new IOException("FAO market video index has no dated video links");
        return List.copyOf(result);
    }

    public void refresh() {
        var attemptedAt = Instant.now();
        try {
            var request = HttpRequest.newBuilder(SOURCE).timeout(Duration.ofSeconds(25))
                    .header("User-Agent", "QiLiang-MarketIntelligence/1.0 (+official video link aggregation)")
                    .GET().build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200 || !"www.fao.org".equals(response.uri().getHost())) {
                response.body().close();
                throw new IOException("FAO market video HTTP " + response.statusCode());
            }
            byte[] bytes;
            try (var stream = response.body()) { bytes = stream.readNBytes(MAX_BYTES + 1); }
            if (bytes.length > MAX_BYTES) throw new IOException("FAO market video index too large");
            save(parse(new String(bytes, StandardCharsets.UTF_8), attemptedAt), attemptedAt);
        } catch (Exception error) {
            LOG.warn("FAO market video link sync failed: {}", error.toString());
            jdbc.sql("""
                    INSERT INTO market_intelligence.source_sync_state (source_code,last_attempt_at,last_error)
                    VALUES (:source,:at,:error)
                    ON CONFLICT (source_code) DO UPDATE SET last_attempt_at=:at,last_error=:error
                    """).param("source", CODE).param("at", OffsetDateTime.ofInstant(attemptedAt, ZoneOffset.UTC))
                    .param("error", error.getClass().getSimpleName()).update();
        }
    }

    public void save(List<Video> videos, Instant fetchedAt) {
        transactions.executeWithoutResult(status -> {
            template.batchUpdate("""
                    INSERT INTO market_intelligence.news_headline (source_code,article_url,title,published_at,fetched_at)
                    VALUES ('fao-market-video',?,?,?,?)
                    ON CONFLICT (source_code,article_url) DO UPDATE SET
                        title=EXCLUDED.title,published_at=EXCLUDED.published_at,fetched_at=EXCLUDED.fetched_at
                    """, videos, 40, (statement, item) -> {
                statement.setString(1, item.url());
                statement.setString(2, item.title());
                statement.setObject(3, item.publishedOn().atStartOfDay().atOffset(ZoneOffset.UTC));
                statement.setObject(4, OffsetDateTime.ofInstant(fetchedAt, ZoneOffset.UTC));
            });
            var latest = videos.stream().map(Video::publishedOn).max(LocalDate::compareTo).orElseThrow();
            jdbc.sql("""
                    INSERT INTO market_intelligence.source_sync_state
                        (source_code,last_attempt_at,last_success_at,latest_period,last_error)
                    VALUES (:source,:at,:at,:latest,NULL)
                    ON CONFLICT (source_code) DO UPDATE SET
                        last_attempt_at=:at,last_success_at=:at,latest_period=:latest,last_error=NULL
                    """).param("source", CODE).param("at", OffsetDateTime.ofInstant(fetchedAt, ZoneOffset.UTC))
                    .param("latest", latest).update();
        });
    }

    @Component
    @Profile("!test")
    @ConditionalOnProperty(name = "qiqihar.market-intelligence.fao-video.enabled", havingValue = "true", matchIfMissing = true)
    static class Refresh {
        private final FaoMarketVideoFeed feed;
        Refresh(FaoMarketVideoFeed feed) { this.feed = feed; }
        @Scheduled(scheduler = "officialNewsScheduler",
                initialDelayString = "${qiqihar.market-intelligence.fao-video.initial-delay:55s}",
                fixedDelayString = "${qiqihar.market-intelligence.fao-video.refresh-delay:2m}")
        public void run() { feed.refresh(); }
    }
}
