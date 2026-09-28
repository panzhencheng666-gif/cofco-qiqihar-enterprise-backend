package com.cofco.qiqihar.graintrade.marketintelligence;

import com.cofco.qiqihar.graintrade.shared.interfaceadapter.ApiResponse;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Stores dated links from USDA NASS's public video index, not video files or playback rights. */
@Component
public class NassVideoNewsFeed {
    static final URI SOURCE = URI.create("https://www.nass.usda.gov/Newsroom/Video_Features/index.php");
    static final String CODE = "usda-nass-video";
    private static final Logger LOG = LoggerFactory.getLogger(NassVideoNewsFeed.class);
    private static final int MAX_BYTES = 1_000_000;
    private static final Pattern DATED_LINK = Pattern.compile(
            "<(?:p|li)\\b[^>]*>\\s*([0-9]{2}/[0-9]{2}/[0-9]{2})\\s*(?:&nbsp;|\\s)*<a\\b[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TAG = Pattern.compile("<[^>]*>");
    private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");
    private static final DateTimeFormatter SOURCE_DATE = DateTimeFormatter.ofPattern("MM/dd/uu");
    private final JdbcClient jdbc;
    private final JdbcTemplate template;
    private final TransactionTemplate transactions;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    public NassVideoNewsFeed(JdbcClient jdbc, JdbcTemplate template,
                             PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.template = template;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public record Video(String title, String url, LocalDate publishedOn) { }
    public record VideoItem(String sourceName, String title, String url, LocalDate publishedOn,
                            Instant fetchedAt, String sourcePageUrl) { }

    static List<Video> parse(String html, Instant fetchedAt) throws IOException {
        var matcher = DATED_LINK.matcher(html);
        var result = new ArrayList<Video>();
        while (matcher.find() && result.size() < 40) {
            try {
                var publishedOn = LocalDate.parse(matcher.group(1), SOURCE_DATE);
                if (publishedOn.isAfter(fetchedAt.atZone(ZoneOffset.UTC).toLocalDate().plusDays(1))) continue;
                var sourceUrl = URI.create(matcher.group(2));
                var host = sourceUrl.getHost();
                if (host == null || !(host.equals("www.youtube.com") || host.equals("youtube.com")
                        || host.equals("youtu.be")) || !"https".equals(sourceUrl.getScheme())) continue;
                String id = host.equals("youtu.be") ? sourceUrl.getPath().replaceFirst("^/", "")
                        : sourceUrl.getPath().startsWith("/live/")
                                ? sourceUrl.getPath().substring(6) : queryVideoId(sourceUrl.getRawQuery());
                if (id == null || !VIDEO_ID.matcher(id).matches()) continue;
                var title = TAG.matcher(matcher.group(3)).replaceAll("").replace("&amp;", "&")
                        .replace("&quot;", "\"").replace("&nbsp;", " ").trim();
                if (title.isBlank() || title.length() > 500) continue;
                result.add(new Video(title, "https://www.youtube.com/watch?v=" + id, publishedOn));
            } catch (RuntimeException invalid) {
                // Skip malformed index entries without inventing publication dates.
            }
        }
        if (result.isEmpty()) throw new IOException("NASS video index has no dated official video links");
        return List.copyOf(result);
    }

    private static String queryVideoId(String query) {
        if (query == null) return null;
        for (var part : query.split("&")) {
            if (part.startsWith("v=")) return part.substring(2);
        }
        return null;
    }

    public void refresh() {
        var attemptedAt = Instant.now();
        try {
            var request = HttpRequest.newBuilder(SOURCE).timeout(Duration.ofSeconds(25))
                    .header("User-Agent", "QiLiang-MarketIntelligence/1.0 (+official video link aggregation)")
                    .GET().build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200 || !"www.nass.usda.gov".equals(response.uri().getHost())) {
                response.body().close();
                throw new IOException("NASS video index HTTP " + response.statusCode());
            }
            byte[] bytes;
            try (var stream = response.body()) { bytes = stream.readNBytes(MAX_BYTES + 1); }
            if (bytes.length > MAX_BYTES) throw new IOException("NASS video index too large");
            save(parse(new String(bytes, StandardCharsets.UTF_8), attemptedAt), attemptedAt);
        } catch (Exception error) {
            LOG.warn("NASS video link sync failed: {}", error.toString());
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
                    VALUES ('usda-nass-video',?,?,?,?)
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
    @ConditionalOnProperty(name = "qiqihar.market-intelligence.nass-video.enabled", havingValue = "true", matchIfMissing = true)
    static class Refresh {
        private final NassVideoNewsFeed feed;
        Refresh(NassVideoNewsFeed feed) { this.feed = feed; }
        @Scheduled(initialDelayString = "${qiqihar.market-intelligence.nass-video.initial-delay:50s}",
                fixedDelayString = "${qiqihar.market-intelligence.nass-video.refresh-delay:2m}")
        public void run() { feed.refresh(); }
    }

    @RestController
    static class Controller {
        private final JdbcClient jdbc;
        Controller(JdbcClient jdbc) { this.jdbc = jdbc; }

        @GetMapping("/api/v1/market-intelligence/news/videos")
        public ApiResponse<List<VideoItem>> list() {
            var videos = jdbc.sql("""
                    SELECT source_code,title,article_url,published_at,fetched_at
                    FROM market_intelligence.news_headline
                    WHERE source_code IN ('usda-nass-video', 'fao-market-video')
                    ORDER BY published_at DESC, article_url LIMIT 40
                    """).query((rs, row) -> new VideoItem(
                    "fao-market-video".equals(rs.getString("source_code")) ? "FAO 市场与贸易" : "USDA NASS",
                    rs.getString("title"),
                    rs.getString("article_url"), rs.getObject("published_at", OffsetDateTime.class)
                            .toInstant().atZone(ZoneOffset.UTC).toLocalDate(),
                    rs.getObject("fetched_at", OffsetDateTime.class).toInstant(),
                    "fao-market-video".equals(rs.getString("source_code"))
                            ? FaoMarketVideoFeed.SOURCE.toString() : SOURCE.toString())).list();
            return new ApiResponse<>(videos);
        }
    }
}
