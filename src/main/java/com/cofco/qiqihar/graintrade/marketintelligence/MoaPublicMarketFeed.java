package com.cofco.qiqihar.graintrade.marketintelligence;

import com.cofco.qiqihar.graintrade.shared.interfaceadapter.ApiResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.net.URI;
import java.net.URLEncoder;
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
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
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

/** Official MOA public monitoring pages. Source dates are publication dates, not tick timestamps. */
@Component
public class MoaPublicMarketFeed {
    static final URI INDEX_PAGE = URI.create("https://scs.moa.gov.cn/jcyj/index.htm");
    static final URI INDEX_CHART = URI.create("https://scs.moa.gov.cn/cst/index.htm");
    static final URI INDEX_DATA = URI.create("https://scs.moa.gov.cn/nyb/getIndexByTenDay");
    private static final String SOURCE = "moa-public-monitor";
    private static final int MAX_BYTES = 1_000_000;
    private static final Logger LOG = LoggerFactory.getLogger(MoaPublicMarketFeed.class);
    private static final Pattern TOKEN = Pattern.compile("token:'([^']{1,128})'");
    private static final Pattern HEADLINE = Pattern.compile(
            "href=\"(\\./[0-9]{6}/t[0-9]+_[0-9]+\\.htm)\"[^>]*title='([^']{1,300})'.{0,500}?<span class=\"sj_gztzri\">([0-9]{4}-[0-9]{2}-[0-9]{2})",
            Pattern.DOTALL);
    private static final Set<String> ALLOWED_SERIES = Set.of(
            "农产品批发价格200指数", "“菜篮子”产品批发价格指数", "粮油产品批发价格指数",
            "畜产品价格指数", "水产品价格指数", "蔬菜价格指数", "水果价格指数",
            "粮食价格指数", "食用油价格指数");
    private static final Map<String, String> SERIES_CODES = Map.of(
            "农产品批发价格200指数", "agri-200", "“菜篮子”产品批发价格指数", "basket",
            "粮油产品批发价格指数", "grain-oil", "畜产品价格指数", "livestock",
            "水产品价格指数", "aquatic", "蔬菜价格指数", "vegetable",
            "水果价格指数", "fruit", "粮食价格指数", "grain", "食用油价格指数", "edible-oil");

    private final JdbcClient jdbc;
    private final JdbcTemplate template;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;
    private final HttpClient http = createOfficialSourceClient();

    static HttpClient createOfficialSourceClient() {
        try {
            // The ministry's public endpoint chains to CFCA EV ROOT, absent from some JDK stores.
            // Keep this trust anchor scoped to the MOA client; never disable TLS verification.
            var certificates = CertificateFactory.getInstance("X.509");
            var trust = KeyStore.getInstance("PKCS12");
            trust.load(null, null);
            try (var stream = MoaPublicMarketFeed.class.getResourceAsStream("/certs/cfca-ev-root.pem")) {
                if (stream == null) throw new IOException("CFCA official-source trust anchor unavailable");
                trust.setCertificateEntry("cfca-ev-root", certificates.generateCertificate(stream));
            }
            var managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            managers.init(trust);
            var tls = SSLContext.getInstance("TLS");
            tls.init(null, managers.getTrustManagers(), null);
            return HttpClient.newBuilder().sslContext(tls).connectTimeout(Duration.ofSeconds(8))
                    .followRedirects(HttpClient.Redirect.NORMAL).build();
        } catch (Exception error) {
            throw new IllegalStateException("Official MOA TLS trust could not be initialized", error);
        }
    }

    public MoaPublicMarketFeed(JdbcClient jdbc, JdbcTemplate template, ObjectMapper mapper,
                               PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.template = template;
        this.transactions = new TransactionTemplate(transactionManager);
        this.mapper = mapper;
    }

    public record Headline(String title, String url, LocalDate publishedOn, Instant fetchedAt) { }
    public record Quote(String series, String name, LocalDate period, BigDecimal value,
                        String sourceUrl, Instant fetchedAt) { }
    public record Feed(List<Quote> quotes, List<Headline> headlines, Instant lastSuccessAt, String lastError) { }

    static List<Headline> parseHeadlines(String html, Instant fetchedAt) throws IOException {
        var result = new ArrayList<Headline>();
        var matcher = HEADLINE.matcher(html);
        while (matcher.find() && result.size() < 40) {
            var date = LocalDate.parse(matcher.group(3));
            if (date.isAfter(fetchedAt.atZone(ZoneOffset.UTC).toLocalDate().plusDays(1))) continue;
            var url = INDEX_PAGE.resolve(matcher.group(1));
            if (!"scs.moa.gov.cn".equals(url.getHost())) continue;
            var title = matcher.group(2).replace("&amp;", "&").replace("&quot;", "\"").trim();
            if (!title.isBlank()) result.add(new Headline(title, url.toString(), date, fetchedAt));
        }
        if (result.isEmpty()) throw new IOException("MOA monitoring index has no valid headlines");
        return result;
    }

    static List<Quote> parseQuotes(JsonNode root, Instant fetchedAt) throws IOException {
        var content = root.path("content");
        if (!content.isArray() || content.isEmpty()) throw new IOException("MOA index response has no observations");
        var result = new ArrayList<Quote>();
        for (var item : content) {
            LocalDate date;
            try { date = LocalDate.parse(item.path("publishDate").asText()); }
            catch (RuntimeException error) { continue; }
            if (date.isAfter(fetchedAt.atZone(ZoneOffset.UTC).toLocalDate().plusDays(1))) continue;
            for (var index : item.path("indexData")) {
                var name = index.path("indexName").asText();
                if (!ALLOWED_SERIES.contains(name)) continue;
                try {
                    var value = new BigDecimal(index.path("indexValue").asText());
                    if (value.signum() > 0 && value.compareTo(new BigDecimal("100000")) < 0)
                        result.add(new Quote(SERIES_CODES.get(name), name, date, value, INDEX_CHART.toString(), fetchedAt));
                } catch (NumberFormatException ignored) { /* Reject malformed observations. */ }
            }
        }
        if (result.stream().noneMatch(quote -> "grain".equals(quote.series())))
            throw new IOException("MOA index response has no grain price index");
        return result;
    }

    private String get(URI uri) throws Exception {
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(25))
                .header("User-Agent", "QiLiang-MarketIntelligence/1.0 (+official public index monitor)")
                .GET().build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200 || !"scs.moa.gov.cn".equals(response.uri().getHost())) {
            response.body().close(); throw new IOException("MOA source HTTP " + response.statusCode());
        }
        try (var stream = response.body()) {
            var bytes = stream.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new IOException("MOA source page too large");
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private JsonNode postIndices(String token) throws Exception {
        var request = HttpRequest.newBuilder(INDEX_DATA).timeout(Duration.ofSeconds(25))
                .header("User-Agent", "QiLiang-MarketIntelligence/1.0 (+official public index monitor)")
                .header("Referer", INDEX_CHART.toString())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("token=" + URLEncoder.encode(token, StandardCharsets.UTF_8))).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200 || !"scs.moa.gov.cn".equals(response.uri().getHost())) {
            response.body().close(); throw new IOException("MOA index HTTP " + response.statusCode());
        }
        try (var stream = response.body()) {
            var bytes = stream.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new IOException("MOA index response too large");
            return mapper.readTree(bytes);
        }
    }

    public void refresh() {
        var attemptedAt = Instant.now();
        try {
            var html = get(INDEX_PAGE);
            var chart = get(INDEX_CHART);
            var token = TOKEN.matcher(chart);
            if (!token.find()) throw new IOException("MOA public chart token unavailable");
            save(parseHeadlines(html, attemptedAt), parseQuotes(postIndices(token.group(1)), attemptedAt), attemptedAt);
        } catch (Exception error) {
            LOG.warn("MOA public market sync failed: {}", error.toString());
            jdbc.sql("""
                    INSERT INTO market_intelligence.source_sync_state (source_code,last_attempt_at,last_error)
                    VALUES (:source,:at,:error)
                    ON CONFLICT (source_code) DO UPDATE SET last_attempt_at=:at,last_error=:error
                    """).param("source", SOURCE).param("at", OffsetDateTime.ofInstant(attemptedAt, ZoneOffset.UTC))
                    .param("error", error.getClass().getSimpleName()).update();
        }
    }

    public void save(List<Headline> headlines, List<Quote> quotes, Instant fetchedAt) {
        // refresh invokes this method directly, so the transaction must not depend on a proxy.
        transactions.executeWithoutResult(status -> saveBatch(headlines, quotes, fetchedAt));
    }

    private void saveBatch(List<Headline> headlines, List<Quote> quotes, Instant fetchedAt) {
        template.batchUpdate("""
                INSERT INTO market_intelligence.news_headline (source_code,article_url,title,published_at,fetched_at)
                VALUES ('moa-public-monitor',?,?,?,?)
                ON CONFLICT (source_code,article_url) DO UPDATE SET title=EXCLUDED.title,published_at=EXCLUDED.published_at,fetched_at=EXCLUDED.fetched_at
                """, headlines, 40, (statement, item) -> {
            statement.setString(1, item.url()); statement.setString(2, item.title());
            statement.setObject(3, item.publishedOn().atStartOfDay().atOffset(ZoneOffset.UTC));
            statement.setObject(4, OffsetDateTime.ofInstant(fetchedAt, ZoneOffset.UTC));
        });
        template.batchUpdate("""
                INSERT INTO market_intelligence.china_daily_index (series_code,period,value,source_url,fetched_at)
                VALUES (?,?,?,?,?)
                ON CONFLICT (series_code,period) DO UPDATE SET value=EXCLUDED.value,source_url=EXCLUDED.source_url,fetched_at=EXCLUDED.fetched_at
                """, quotes, 90, (statement, item) -> {
            statement.setString(1, item.series()); statement.setObject(2, item.period());
            statement.setBigDecimal(3, item.value()); statement.setString(4, item.sourceUrl());
            statement.setObject(5, OffsetDateTime.ofInstant(fetchedAt, ZoneOffset.UTC));
        });
        var latest = quotes.stream().map(Quote::period).max(LocalDate::compareTo).orElseThrow();
        jdbc.sql("""
                INSERT INTO market_intelligence.source_sync_state (source_code,last_attempt_at,last_success_at,latest_period,last_error)
                VALUES (:source,:at,:at,:latest,NULL)
                ON CONFLICT (source_code) DO UPDATE SET last_attempt_at=:at,last_success_at=:at,latest_period=:latest,last_error=NULL
                """).param("source", SOURCE).param("at", OffsetDateTime.ofInstant(fetchedAt, ZoneOffset.UTC))
                .param("latest", latest).update();
    }

    public Feed feed() {
        var quotes = jdbc.sql("""
                SELECT series_code,period,value,source_url,fetched_at FROM market_intelligence.china_daily_index
                WHERE period >= CURRENT_DATE - INTERVAL '45 days' ORDER BY period DESC,series_code LIMIT 400
                """).query((rs, row) -> new Quote(rs.getString("series_code"), "", rs.getObject("period", LocalDate.class),
                rs.getBigDecimal("value"), rs.getString("source_url"),
                rs.getObject("fetched_at", OffsetDateTime.class).toInstant())).list();
        var headlines = jdbc.sql("""
                SELECT title,article_url,published_at,fetched_at FROM market_intelligence.news_headline
                WHERE source_code='moa-public-monitor' ORDER BY published_at DESC LIMIT 30
                """).query((rs, row) -> new Headline(rs.getString("title"), rs.getString("article_url"),
                rs.getObject("published_at", OffsetDateTime.class).toLocalDate(),
                rs.getObject("fetched_at", OffsetDateTime.class).toInstant())).list();
        return jdbc.sql("SELECT last_success_at,last_error FROM market_intelligence.source_sync_state WHERE source_code=:source")
                .param("source", SOURCE).query((rs, row) -> new Feed(quotes, headlines,
                        rs.getObject("last_success_at", OffsetDateTime.class) == null ? null
                                : rs.getObject("last_success_at", OffsetDateTime.class).toInstant(),
                        rs.getString("last_error"))).optional().orElse(new Feed(quotes, headlines, null, null));
    }

    @Component
    @Profile("!test")
    @ConditionalOnProperty(name = "qiqihar.market-intelligence.moa.enabled", havingValue = "true", matchIfMissing = true)
    static class Refresh {
        private final MoaPublicMarketFeed feed;
        Refresh(MoaPublicMarketFeed feed) { this.feed = feed; }
        @Scheduled(initialDelayString = "${qiqihar.market-intelligence.moa.initial-delay:45s}",
                fixedDelayString = "${qiqihar.market-intelligence.moa.refresh-delay:5m}")
        public void run() { feed.refresh(); }
    }

    @RestController
    static class Controller {
        private final MoaPublicMarketFeed feed;
        Controller(MoaPublicMarketFeed feed) { this.feed = feed; }
        @GetMapping("/api/v1/market-intelligence/china/overview")
        public ApiResponse<Feed> list() { return new ApiResponse<>(feed.feed()); }
    }
}
