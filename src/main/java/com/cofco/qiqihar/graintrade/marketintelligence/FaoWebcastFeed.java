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
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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

/** FAO webcast schedule and replay page links; no stream or video bytes are redistributed. */
@Component
public class FaoWebcastFeed {
    static final URI SOURCE = URI.create("https://www.fao.org/webcast/");
    static final String CODE = "fao-webcast";
    private static final ZoneId ROME = ZoneId.of("Europe/Rome");
    private static final Logger LOG = LoggerFactory.getLogger(FaoWebcastFeed.class);
    private static final int MAX_BYTES = 1_000_000;
    private static final Pattern BLOCK = Pattern.compile(
            "<div class=\"d-list d-list-video d-list-webcast\">(.*?)</div>\\s*</div>", Pattern.DOTALL);
    private static final Pattern TITLE_LINK = Pattern.compile(
            "<h5 class=\"title-link\">\\s*<a href=\"([^\"]+)\">([^<]+)</a>", Pattern.DOTALL);
    private static final Pattern START = Pattern.compile(
            "<span class=\"date\">\\s*([0-9]{1,2} [A-Za-z]{3} [0-9]{4}, [0-9]{2}:[0-9]{2})\\s*</span>");
    private static final DateTimeFormatter SOURCE_TIME = DateTimeFormatter.ofPattern("d MMM uuuu, HH:mm", Locale.ENGLISH);
    private final JdbcClient jdbc;
    private final JdbcTemplate template;
    private final TransactionTemplate transactions;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    public FaoWebcastFeed(JdbcClient jdbc, JdbcTemplate template,
                          PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.template = template;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public record Event(String title, String url, Instant startsAt) { }
    public record EventItem(String sourceName, String title, String url, Instant startsAt,
                            Instant fetchedAt, String sourcePageUrl) { }

    static List<Event> parse(String html, Instant fetchedAt) throws IOException {
        var blocks = BLOCK.matcher(html);
        var events = new ArrayList<Event>();
        while (blocks.find() && events.size() < 40) {
            var link = TITLE_LINK.matcher(blocks.group(1));
            var time = START.matcher(blocks.group(1));
            if (!link.find() || !time.find()) continue;
            try {
                var url = URI.create(link.group(1));
                if (!"https".equals(url.getScheme()) || !"www.fao.org".equals(url.getHost())
                        || !url.getPath().startsWith("/webcast/detail/")) continue;
                var startsAt = LocalDateTime.parse(time.group(1), SOURCE_TIME).atZone(ROME).toInstant();
                if (startsAt.isAfter(fetchedAt.plus(Duration.ofDays(90)))) continue;
                var title = link.group(2).replace("&amp;", "&").replace("&#160;", " ")
                        .replace("&#39;", "'").replace("&quot;", "\"").trim();
                if (title.isBlank() || title.length() > 500) continue;
                events.add(new Event(title, url.toString(), startsAt));
            } catch (RuntimeException invalid) {
                // The listing is authoritative; malformed entries are skipped without guessed times.
            }
        }
        if (events.isEmpty()) throw new IOException("FAO webcast page has no dated official events");
        return List.copyOf(events);
    }

    public void refresh() {
        var attemptedAt = Instant.now();
        try {
            var request = HttpRequest.newBuilder(SOURCE).timeout(Duration.ofSeconds(25))
                    .header("User-Agent", "QiLiang-MarketIntelligence/1.0 (+official webcast link aggregation)")
                    .GET().build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200 || !"www.fao.org".equals(response.uri().getHost())) {
                response.body().close();
                throw new IOException("FAO webcast HTTP " + response.statusCode());
            }
            byte[] bytes;
            try (var stream = response.body()) { bytes = stream.readNBytes(MAX_BYTES + 1); }
            if (bytes.length > MAX_BYTES) throw new IOException("FAO webcast page too large");
            save(parse(new String(bytes, StandardCharsets.UTF_8), attemptedAt), attemptedAt);
        } catch (Exception error) {
            LOG.warn("FAO webcast sync failed: {}", error.toString());
            jdbc.sql("""
                    INSERT INTO market_intelligence.source_sync_state (source_code,last_attempt_at,last_error)
                    VALUES (:source,:at,:error)
                    ON CONFLICT (source_code) DO UPDATE SET last_attempt_at=:at,last_error=:error
                    """).param("source", CODE).param("at", OffsetDateTime.ofInstant(attemptedAt, ZoneOffset.UTC))
                    .param("error", error.getClass().getSimpleName()).update();
        }
    }

    public void save(List<Event> events, Instant fetchedAt) {
        transactions.executeWithoutResult(status -> {
            template.batchUpdate("""
                    INSERT INTO market_intelligence.webcast_event (source_code,event_url,title,starts_at,fetched_at)
                    VALUES ('fao-webcast',?,?,?,?)
                    ON CONFLICT (source_code,event_url) DO UPDATE SET
                        title=EXCLUDED.title,starts_at=EXCLUDED.starts_at,fetched_at=EXCLUDED.fetched_at
                    """, events, 40, (statement, item) -> {
                statement.setString(1, item.url());
                statement.setString(2, item.title());
                statement.setObject(3, OffsetDateTime.ofInstant(item.startsAt(), ZoneOffset.UTC));
                statement.setObject(4, OffsetDateTime.ofInstant(fetchedAt, ZoneOffset.UTC));
            });
            jdbc.sql("""
                    INSERT INTO market_intelligence.source_sync_state
                        (source_code,last_attempt_at,last_success_at,latest_period,last_error)
                    VALUES (:source,:at,:at,NULL,NULL)
                    ON CONFLICT (source_code) DO UPDATE SET
                        last_attempt_at=:at,last_success_at=:at,latest_period=NULL,last_error=NULL
                    """).param("source", CODE).param("at", OffsetDateTime.ofInstant(fetchedAt, ZoneOffset.UTC))
                    .update();
        });
    }

    @Component
    @Profile("!test")
    @ConditionalOnProperty(name = "qiqihar.market-intelligence.fao-webcast.enabled", havingValue = "true", matchIfMissing = true)
    static class Refresh {
        private final FaoWebcastFeed feed;
        Refresh(FaoWebcastFeed feed) { this.feed = feed; }
        @Scheduled(scheduler = "officialNewsScheduler",
                initialDelayString = "${qiqihar.market-intelligence.fao-webcast.initial-delay:1m}",
                fixedDelayString = "${qiqihar.market-intelligence.fao-webcast.refresh-delay:2m}")
        public void run() { feed.refresh(); }
    }

    @RestController
    static class Controller {
        private final JdbcClient jdbc;
        Controller(JdbcClient jdbc) { this.jdbc = jdbc; }

        @GetMapping("/api/v1/market-intelligence/news/webcasts")
        public ApiResponse<List<EventItem>> list() {
            var events = jdbc.sql("""
                    SELECT title,event_url,starts_at,fetched_at FROM market_intelligence.webcast_event
                    WHERE source_code='fao-webcast' ORDER BY starts_at DESC LIMIT 40
                    """).query((rs, row) -> new EventItem("FAO Webcast", rs.getString("title"),
                    rs.getString("event_url"), rs.getObject("starts_at", OffsetDateTime.class).toInstant(),
                    rs.getObject("fetched_at", OffsetDateTime.class).toInstant(), SOURCE.toString())).list();
            return new ApiResponse<>(events);
        }
    }
}
