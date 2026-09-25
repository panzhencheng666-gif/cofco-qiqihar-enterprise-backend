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
import org.springframework.transaction.annotation.Transactional;

/** Headlines and links from the Ministry's public department-news index. */
@Component
public class MoaDepartmentNewsFeed {
    static final URI SOURCE = URI.create("https://www.moa.gov.cn/xw/zwdt/");
    static final String CODE = "moa-department-news";
    private static final Logger LOG = LoggerFactory.getLogger(MoaDepartmentNewsFeed.class);
    private static final int MAX_BYTES = 1_000_000;
    private static final Pattern ITEM = Pattern.compile("<li class=\"ztlb\">\\s*<a href=\"([^\"]+)\"[^>]*title='([^']{1,500})'[^>]*>.*?</a>\\s*<span>\\s*([0-9]{4}-[0-9]{2}-[0-9]{2})\\s*</span>", Pattern.DOTALL);
    private static final Pattern RELEVANT = Pattern.compile("粮|秋收|夏收|耕地|农机|农业生产|农作物|农业机械化|农产品|大豆|玉米|小麦|水稻|油料|畜|渔|灾|虫|肥|种植|丰收");
    private final JdbcClient jdbc;
    private final JdbcTemplate template;
    private final HttpClient http = MoaPublicMarketFeed.createOfficialSourceClient();

    public MoaDepartmentNewsFeed(JdbcClient jdbc, JdbcTemplate template) {
        this.jdbc = jdbc;
        this.template = template;
    }

    public record Headline(String title, String url, LocalDate publishedOn) { }

    static List<Headline> parse(String html, Instant fetchedAt) throws IOException {
        var matches = ITEM.matcher(html);
        var headlines = new ArrayList<Headline>();
        while (matches.find() && headlines.size() < 40) {
            var title = matches.group(2).replace("&amp;", "&").replace("&quot;", "\"").trim();
            if (!RELEVANT.matcher(title).find()) continue;
            LocalDate date;
            try { date = LocalDate.parse(matches.group(3)); }
            catch (RuntimeException invalid) { continue; }
            if (date.isAfter(fetchedAt.atZone(ZoneOffset.UTC).toLocalDate().plusDays(1))) continue;
            var url = SOURCE.resolve(matches.group(1));
            if (!"https".equals(url.getScheme()) || !"www.moa.gov.cn".equals(url.getHost())) continue;
            headlines.add(new Headline(title, url.toString(), date));
        }
        if (headlines.isEmpty()) throw new IOException("MOA department-news index has no relevant dated headlines");
        return List.copyOf(headlines);
    }

    public void refresh() {
        var attemptedAt = Instant.now();
        try {
            var request = HttpRequest.newBuilder(SOURCE).timeout(Duration.ofSeconds(25))
                    .header("User-Agent", "QiLiang-MarketIntelligence/1.0 (+official public headline monitor)")
                    .GET().build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200 || !"www.moa.gov.cn".equals(response.uri().getHost())) {
                response.body().close();
                throw new IOException("MOA department news HTTP " + response.statusCode());
            }
            byte[] bytes;
            try (var stream = response.body()) { bytes = stream.readNBytes(MAX_BYTES + 1); }
            if (bytes.length > MAX_BYTES) throw new IOException("MOA department news page too large");
            save(parse(new String(bytes, StandardCharsets.UTF_8), attemptedAt), attemptedAt);
        } catch (Exception error) {
            LOG.warn("MOA department-news sync failed: {}", error.toString());
            jdbc.sql("""
                    INSERT INTO market_intelligence.source_sync_state (source_code,last_attempt_at,last_error)
                    VALUES (:source,:at,:error)
                    ON CONFLICT (source_code) DO UPDATE SET last_attempt_at=:at,last_error=:error
                    """).param("source", CODE).param("at", OffsetDateTime.ofInstant(attemptedAt, ZoneOffset.UTC))
                    .param("error", error.getClass().getSimpleName()).update();
        }
    }

    @Transactional
    public void save(List<Headline> headlines, Instant fetchedAt) {
        template.batchUpdate("""
                INSERT INTO market_intelligence.news_headline (source_code,article_url,title,published_at,fetched_at)
                VALUES ('moa-department-news',?,?,?,?)
                ON CONFLICT (source_code,article_url) DO UPDATE SET
                    title=EXCLUDED.title,published_at=EXCLUDED.published_at,fetched_at=EXCLUDED.fetched_at
                """, headlines, 40, (statement, item) -> {
            statement.setString(1, item.url()); statement.setString(2, item.title());
            statement.setObject(3, item.publishedOn().atStartOfDay().atOffset(ZoneOffset.UTC));
            statement.setObject(4, OffsetDateTime.ofInstant(fetchedAt, ZoneOffset.UTC));
        });
        var latest = headlines.stream().map(Headline::publishedOn).max(LocalDate::compareTo).orElseThrow();
        jdbc.sql("""
                INSERT INTO market_intelligence.source_sync_state (source_code,last_attempt_at,last_success_at,latest_period,last_error)
                VALUES (:source,:at,:at,:latest,NULL)
                ON CONFLICT (source_code) DO UPDATE SET last_attempt_at=:at,last_success_at=:at,latest_period=:latest,last_error=NULL
                """).param("source", CODE).param("at", OffsetDateTime.ofInstant(fetchedAt, ZoneOffset.UTC))
                .param("latest", latest).update();
    }

    @Component
    @Profile("!test")
    @ConditionalOnProperty(name = "qiqihar.market-intelligence.moa-news.enabled", havingValue = "true", matchIfMissing = true)
    static class Refresh {
        private final MoaDepartmentNewsFeed feed;
        Refresh(MoaDepartmentNewsFeed feed) { this.feed = feed; }
        @Scheduled(initialDelayString = "${qiqihar.market-intelligence.moa-news.initial-delay:35s}",
                fixedDelayString = "${qiqihar.market-intelligence.moa-news.refresh-delay:5m}")
        public void run() { feed.refresh(); }
    }
}
