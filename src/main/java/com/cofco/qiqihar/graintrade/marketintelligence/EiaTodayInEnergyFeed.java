package com.cofco.qiqihar.graintrade.marketintelligence;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
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
import org.w3c.dom.Element;

/** EIA's official RSS provides dated headlines and links; article bodies and images are not copied. */
@Component
public class EiaTodayInEnergyFeed {
    static final URI SOURCE = URI.create("https://www.eia.gov/rss/todayinenergy.xml");
    static final String CODE = "eia-today-in-energy";
    private static final Logger LOG = LoggerFactory.getLogger(EiaTodayInEnergyFeed.class);
    private static final int MAX_BYTES = 1_000_000;
    private final JdbcClient jdbc;
    private final JdbcTemplate template;
    private final TransactionTemplate transactions;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    public EiaTodayInEnergyFeed(JdbcClient jdbc, JdbcTemplate template,
                                PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.template = template;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public record Headline(String title, String url, Instant publishedAt) { }

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
        if (!"rss".equals(document.getDocumentElement().getTagName())) throw new IOException("Unexpected EIA RSS root");
        var items = document.getElementsByTagName("item");
        var result = new ArrayList<Headline>();
        for (int index = 0; index < Math.min(items.getLength(), 80); index++) {
            var item = (Element) items.item(index);
            var title = text(item, "title").trim();
            var link = text(item, "link").trim();
            var date = text(item, "pubDate").trim().replaceAll("\\s+", " ");
            if (title.isBlank() || title.length() > 500 || link.length() > 1000) continue;
            try {
                var url = URI.create(link);
                if (!"https".equals(url.getScheme()) || !"www.eia.gov".equals(url.getHost())
                        || !"/todayinenergy/detail.php".equals(url.getPath())) continue;
                // The EIA feed uses the literal EST/EDT abbreviations, including in summer.
                date = date.replaceAll(" EST$", " -0500").replaceAll(" EDT$", " -0400");
                var publishedAt = OffsetDateTime.parse(date, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                if (publishedAt.isAfter(fetchedAt.plus(Duration.ofMinutes(5)))) continue;
                result.add(new Headline(title, url.toString(), publishedAt));
            } catch (RuntimeException invalid) {
                // One malformed source item must not suppress other valid headlines.
            }
        }
        if (result.isEmpty()) throw new IOException("EIA RSS has no valid dated headlines");
        return List.copyOf(result);
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
            if (response.statusCode() != 200 || !"www.eia.gov".equals(response.uri().getHost())) {
                response.body().close();
                throw new IOException("EIA RSS HTTP " + response.statusCode());
            }
            byte[] bytes;
            try (var stream = response.body()) { bytes = stream.readNBytes(MAX_BYTES + 1); }
            if (bytes.length > MAX_BYTES) throw new IOException("EIA RSS too large");
            save(parse(bytes, attemptedAt), attemptedAt);
        } catch (Exception error) {
            LOG.warn("EIA headline sync failed: {}", error.toString());
            jdbc.sql("""
                    INSERT INTO market_intelligence.source_sync_state (source_code,last_attempt_at,last_error)
                    VALUES (:source,:at,:error)
                    ON CONFLICT (source_code) DO UPDATE SET last_attempt_at=:at,last_error=:error
                    """).param("source", CODE).param("at", OffsetDateTime.ofInstant(attemptedAt, ZoneOffset.UTC))
                    .param("error", error.getClass().getSimpleName()).update();
        }
    }

    public void save(List<Headline> headlines, Instant fetchedAt) {
        transactions.executeWithoutResult(status -> {
            template.batchUpdate("""
                    INSERT INTO market_intelligence.news_headline (source_code,article_url,title,published_at,fetched_at)
                    VALUES ('eia-today-in-energy',?,?,?,?)
                    ON CONFLICT (source_code,article_url) DO UPDATE SET
                        title=EXCLUDED.title,published_at=EXCLUDED.published_at,fetched_at=EXCLUDED.fetched_at
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
                    VALUES (:source,:at,:at,:latest,NULL)
                    ON CONFLICT (source_code) DO UPDATE SET
                        last_attempt_at=:at,last_success_at=:at,latest_period=:latest,last_error=NULL
                    """).param("source", CODE).param("at", OffsetDateTime.ofInstant(fetchedAt, ZoneOffset.UTC))
                    .param("latest", latest.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate()).update();
        });
    }

    @Component
    @Profile("!test")
    @ConditionalOnProperty(name = "qiqihar.market-intelligence.eia.enabled", havingValue = "true", matchIfMissing = true)
    static class Refresh {
        private final EiaTodayInEnergyFeed feed;
        Refresh(EiaTodayInEnergyFeed feed) { this.feed = feed; }
        @Scheduled(initialDelayString = "${qiqihar.market-intelligence.eia.initial-delay:40s}",
                fixedDelayString = "${qiqihar.market-intelligence.eia.refresh-delay:2m}")
        public void run() { feed.refresh(); }
    }
}
