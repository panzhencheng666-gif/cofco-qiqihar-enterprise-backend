package com.cofco.qiqihar.graintrade.marketintelligence;

import com.cofco.qiqihar.graintrade.shared.interfaceadapter.ApiResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
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
import org.w3c.dom.Element;

/** Stores only original FAO headlines, publication dates and links; no article body is copied. */
@Component
public class FaoNewsFeed {
    static final URI SOURCE = URI.create("https://www.fao.org/feeds/fao-newsroom-rss");
    private static final Logger LOG = LoggerFactory.getLogger(FaoNewsFeed.class);
    private static final int MAX_BYTES = 1_000_000;
    private final JdbcClient jdbc;
    private final JdbcTemplate template;
    private final TransactionTemplate transactions;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    public FaoNewsFeed(JdbcClient jdbc, JdbcTemplate template,
                       PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.template = template;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public record Headline(String title, String url, Instant publishedAt, Instant fetchedAt) { }
    public record Feed(List<Headline> headlines, Instant lastSuccessAt, String lastError) { }

    static List<Headline> parse(byte[] xml, Instant fetchedAt) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        var document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        if (!"rss".equals(document.getDocumentElement().getTagName())) throw new IOException("Unexpected RSS root");
        var items = document.getElementsByTagName("item");
        var result = new ArrayList<Headline>();
        for (int index = 0; index < Math.min(items.getLength(), 80); index++) {
            var item = (Element) items.item(index);
            var title = text(item, "title").trim();
            var link = text(item, "link").trim();
            var published = text(item, "pubDate").trim();
            if (title.isBlank() || title.length() > 500 || link.length() > 1000) continue;
            URI url;
            Instant date;
            try {
                url = URI.create(link);
                date = java.time.ZonedDateTime.parse(
                        published.endsWith(" Z") ? published.substring(0, published.length() - 2) + " GMT" : published,
                        DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            } catch (RuntimeException badItem) { continue; }
            if (!"https".equals(url.getScheme()) || !"www.fao.org".equals(url.getHost())
                    || date.isAfter(fetchedAt.plus(Duration.ofDays(1)))) continue;
            result.add(new Headline(title, url.toString(), date, fetchedAt));
        }
        if (result.isEmpty()) throw new IOException("FAO RSS has no valid headlines");
        return result;
    }

    private static String text(Element element, String tag) {
        var nodes = element.getElementsByTagName(tag);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent();
    }

    public void refresh() {
        var attemptedAt = Instant.now();
        try {
            var request = HttpRequest.newBuilder(SOURCE).timeout(Duration.ofSeconds(25))
                    .header("Accept", "application/rss+xml, application/xml")
                    .header("User-Agent", "QiLiang-MarketIntelligence/1.0 (+official headline aggregation)")
                    .GET().build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                response.body().close();
                throw new IOException("HTTP " + response.statusCode());
            }
            byte[] bytes;
            try (var stream = response.body()) { bytes = stream.readNBytes(MAX_BYTES + 1); }
            if (bytes.length > MAX_BYTES) throw new IOException("FAO RSS too large");
            save(parse(bytes, attemptedAt), attemptedAt);
        } catch (Exception error) {
            LOG.warn("FAO headline sync failed: {}", error.toString());
            jdbc.sql("""
                    INSERT INTO market_intelligence.source_sync_state (source_code,last_attempt_at,last_error)
                    VALUES ('fao-newsroom-rss',:at,:error)
                    ON CONFLICT (source_code) DO UPDATE SET last_attempt_at=:at,last_error=:error
                    """).param("at", OffsetDateTime.ofInstant(attemptedAt, ZoneOffset.UTC))
                    .param("error", error.getClass().getSimpleName()).update();
        }
    }

    public void save(List<Headline> headlines, Instant fetchedAt) {
        // refresh invokes this method directly, so the transaction must not depend on a proxy.
        transactions.executeWithoutResult(status -> saveBatch(headlines, fetchedAt));
    }

    private void saveBatch(List<Headline> headlines, Instant fetchedAt) {
        template.batchUpdate("""
                INSERT INTO market_intelligence.news_headline
                    (source_code,article_url,title,published_at,fetched_at)
                VALUES ('fao-newsroom-rss',?,?,?,?)
                ON CONFLICT (source_code,article_url) DO UPDATE
                SET title=EXCLUDED.title,published_at=EXCLUDED.published_at,fetched_at=EXCLUDED.fetched_at
                """, headlines, 80, (statement, item) -> {
            statement.setString(1, item.url());
            statement.setString(2, item.title());
            statement.setObject(3, OffsetDateTime.ofInstant(item.publishedAt(), ZoneOffset.UTC));
            statement.setObject(4, OffsetDateTime.ofInstant(fetchedAt, ZoneOffset.UTC));
        });
        var latest = headlines.stream().map(Headline::publishedAt).max(Instant::compareTo).orElseThrow();
        jdbc.sql("""
                INSERT INTO market_intelligence.source_sync_state
                    (source_code,last_attempt_at,last_success_at,latest_period,last_error)
                VALUES ('fao-newsroom-rss',:at,:at,:latest,NULL)
                ON CONFLICT (source_code) DO UPDATE SET
                    last_attempt_at=:at,last_success_at=:at,latest_period=:latest,last_error=NULL
                """).param("at", OffsetDateTime.ofInstant(fetchedAt, ZoneOffset.UTC))
                .param("latest", latest.atZone(ZoneOffset.UTC).toLocalDate()).update();
    }

    public Feed feed() {
        var headlines = jdbc.sql("""
                SELECT title,article_url,published_at,fetched_at
                FROM market_intelligence.news_headline
                WHERE source_code='fao-newsroom-rss'
                ORDER BY published_at DESC LIMIT 30
                """).query((rs, row) -> new Headline(rs.getString("title"), rs.getString("article_url"),
                rs.getObject("published_at", OffsetDateTime.class).toInstant(),
                rs.getObject("fetched_at", OffsetDateTime.class).toInstant())).list();
        return jdbc.sql("""
                SELECT last_success_at,last_error FROM market_intelligence.source_sync_state
                WHERE source_code='fao-newsroom-rss'
                """).query((rs, row) -> new Feed(headlines,
                rs.getObject("last_success_at", OffsetDateTime.class) == null ? null
                        : rs.getObject("last_success_at", OffsetDateTime.class).toInstant(),
                rs.getString("last_error"))).optional().orElse(new Feed(headlines, null, null));
    }

    @Component
    @Profile("!test")
    @ConditionalOnProperty(name = "qiqihar.market-intelligence.fao.enabled", havingValue = "true", matchIfMissing = true)
    static class Refresh {
        private final FaoNewsFeed feed;
        Refresh(FaoNewsFeed feed) { this.feed = feed; }
        @Scheduled(initialDelayString = "${qiqihar.market-intelligence.fao.initial-delay:30s}",
                fixedDelayString = "${qiqihar.market-intelligence.fao.refresh-delay:2m}")
        public void run() { feed.refresh(); }
    }

    @RestController
    static class Controller {
        private final FaoNewsFeed feed;
        Controller(FaoNewsFeed feed) { this.feed = feed; }
        @GetMapping("/api/v1/market-intelligence/news/fao")
        public ApiResponse<Feed> list() { return new ApiResponse<>(feed.feed()); }
    }
}
