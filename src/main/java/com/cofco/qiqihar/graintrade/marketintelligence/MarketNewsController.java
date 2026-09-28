package com.cofco.qiqihar.graintrade.marketintelligence;

import com.cofco.qiqihar.graintrade.shared.interfaceadapter.ApiResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Bounded, source-attributed official headlines; full articles remain on publisher sites. */
@RestController
public class MarketNewsController {
    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
    private final JdbcClient jdbc;

    public MarketNewsController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Headline(String sourceCode, String sourceName, String region,
                           String title, String url, Instant publishedAt, LocalDate publishedOn,
                           String publicationPrecision, Instant fetchedAt) { }

    @GetMapping("/api/v1/market-intelligence/news/latest")
    public ApiResponse<List<Headline>> latest() {
        var headlines = jdbc.sql("""
                WITH ranked AS (
                    SELECT source_code, article_url, title, published_at, fetched_at,
                           ROW_NUMBER() OVER (PARTITION BY CASE WHEN source_code IN
                               ('moa-public-monitor', 'moa-department-news') THEN 'domestic'
                               ELSE 'international' END ORDER BY published_at DESC, article_url) AS position
                    FROM market_intelligence.news_headline
                    WHERE source_code IN ('moa-public-monitor', 'moa-department-news',
                                          'fao-newsroom-rss', 'eia-today-in-energy')
                )
                SELECT source_code, article_url, title, published_at, fetched_at
                FROM ranked WHERE position <= 25
                ORDER BY published_at DESC, article_url
                """).query((rs, row) -> {
            var code = rs.getString("source_code");
            var name = switch (code) {
                case "moa-public-monitor" -> "农业农村部监测";
                case "moa-department-news" -> "农业农村部动态";
                case "fao-newsroom-rss" -> "FAO 新闻";
                case "eia-today-in-energy" -> "美国能源信息署";
                default -> throw new IllegalStateException("Unregistered news source");
            };
            var publishedAt = rs.getObject("published_at", OffsetDateTime.class).toInstant();
            // MOA pages and FAO Newsroom expose a calendar date; FAO RSS's repeated noon
            // timestamp does not establish a verified article publication clock time.
            var dateOnly = code.startsWith("moa-") || "fao-newsroom-rss".equals(code);
            return new Headline(code, name, code.startsWith("moa-") ? "domestic" : "international",
                    rs.getString("title"), rs.getString("article_url"),
                    publishedAt, dateOnly ? publishedAt.atZone(ZoneOffset.UTC).toLocalDate()
                            : publishedAt.atZone(BEIJING).toLocalDate(), dateOnly ? "date" : "instant",
                    rs.getObject("fetched_at", OffsetDateTime.class).toInstant());
        }).list();
        return new ApiResponse<>(headlines);
    }
}
