package com.cofco.qiqihar.graintrade.marketintelligence;

import com.cofco.qiqihar.graintrade.shared.interfaceadapter.ApiResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Published-source health, read from the same durable state used by autonomous collectors. */
@RestController
public class MarketSourceStatusController {
    private final JdbcClient jdbc;

    public MarketSourceStatusController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record SourceStatus(String code, String name, String cadence,
                               Instant lastAttemptAt, Instant lastSuccessAt,
                               LocalDate latestPublishedOn, String lastError) { }

    @GetMapping("/api/v1/market-intelligence/sources/status")
    public ApiResponse<List<SourceStatus>> status() {
        var sources = jdbc.sql("""
                SELECT source.code, source.name, source.cadence,
                       state.last_attempt_at, state.last_success_at,
                       state.latest_period, state.last_error
                FROM (VALUES
                    ('moa-public-monitor', '农业农村部监测', '每日发布 · 自动轮询'),
                    ('moa-department-news', '农业农村部动态', '按源发布 · 自动轮询'),
                    ('fao-newsroom-rss', 'FAO 新闻', '按源发布 · 自动轮询'),
                    ('world-bank-pink-sheet', '世界银行月度价格', '每月发布 · 每日检查'),
                    ('fao-food-price-index', 'FAO 食品价格指数', '每月发布 · 每 6 小时检查')
                ) AS source(code, name, cadence)
                LEFT JOIN market_intelligence.source_sync_state state ON state.source_code = source.code
                ORDER BY CASE source.code
                    WHEN 'moa-public-monitor' THEN 0
                    WHEN 'moa-department-news' THEN 1
                    WHEN 'fao-newsroom-rss' THEN 2
                    WHEN 'world-bank-pink-sheet' THEN 3
                    ELSE 4 END
                """).query((rs, row) -> new SourceStatus(
                rs.getString("code"), rs.getString("name"), rs.getString("cadence"),
                instant(rs.getObject("last_attempt_at", OffsetDateTime.class)),
                instant(rs.getObject("last_success_at", OffsetDateTime.class)),
                rs.getObject("latest_period", LocalDate.class), rs.getString("last_error"))).list();
        return new ApiResponse<>(sources);
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
