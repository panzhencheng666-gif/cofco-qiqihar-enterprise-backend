package com.cofco.qiqihar.graintrade.marketintelligence;

import java.time.*;
import java.util.List;
import java.util.Objects;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/** Read-only, bounded metadata projection; never triggers a search or publishes full text. */
final class NewsDiscoveryRead {
    record Item(String sourceHost, String title, String url, Instant publishedAt,
                LocalDate publishedOn, String publicationPrecision, Instant reviewedAt) {}
    record Snapshot(String state, String searchState, Instant lastSearchCompletedAt,
                    Instant observedAt, List<Item> items) {}
    private final JdbcClient jdbc;
    NewsDiscoveryRead(JdbcClient jdbc) { this.jdbc = Objects.requireNonNull(jdbc); }
    Snapshot snapshot(boolean enabled, Instant deadline, Instant now) {
        if (!enabled) return new Snapshot("DISABLED", "NOT_EVALUATED", null, now, List.of());
        if (deadline == null) return unavailable(now);
        try {
            var schedule = jdbc.sql("""
                SELECT last_state,last_completed_at FROM market_intelligence.news_discovery_search_schedule WHERE slot=1
                """).query((rs, row) -> new SearchStatus(rs.getString(1), instant(rs, "last_completed_at"))).single();
            if (!List.of("NOT_RUN", "RUNNING", "CANDIDATES", "EMPTY", "DEGRADED", "FAILED").contains(schedule.state()))
                return unavailable(now);
            var items = jdbc.sql("""
                SELECT c.article_url,c.source_host,c.review_evidence::text,c.reviewed_at,
                       a.review_reference,a.checked_at,a.expires_at
                FROM market_intelligence.news_discovery_candidate c
                JOIN market_intelligence.news_discovery_source_admission a
                  ON a.origin_uri='https://' || c.source_host || '/'
                WHERE c.review_state='VERIFIED' AND c.review_reason='METADATA_GATES_PASSED'
                  AND c.reviewed_at <= :now AND a.checked_at <= :now AND a.expires_at > :now
                  AND a.metadata_display_allowed
                ORDER BY c.review_evidence->>'publishedOn' DESC,c.reviewed_at DESC,c.article_url
                LIMIT 100
                """).param("now", now.atOffset(ZoneOffset.UTC))
                .query((rs, row) -> project(rs, now)).list().stream().filter(Objects::nonNull).toList();
            // Configured is not proof of a live scheduler or successful search.
            return new Snapshot(deadline.isAfter(now) ? "CONFIGURED" : "EXPIRED",
                schedule.state(), schedule.completed(), now, items);
        } catch (org.springframework.dao.DataAccessException unavailable) {
            return unavailable(now);
        }
    }
    private record SearchStatus(String state, Instant completed) {}
    private static Snapshot unavailable(Instant now) {
        return new Snapshot("UNAVAILABLE", "NOT_EVALUATED", null, now, List.of());
    }
    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
    private static Item project(ResultSet rs, Instant now) throws SQLException {
        try {
            String raw = rs.getString("review_evidence");
            if (raw.length() > 16000) return null;
            var evidence = JsonMapper.builder().build().readTree(raw);
            String url = rs.getString("article_url");
            var page = NewsSearchResults.candidateUri(url);
            if (page == null || !page.toString().equals(url) || !page.getHost().equals(rs.getString("source_host"))
                    || !url.equals(evidence.path("articleUrl").asText())
                    || !url.equals(evidence.path("finalUrl").asText())
                    || !"news-metadata-review-v1".equals(evidence.path("ruleVersion").asText())
                    || !rs.getString("review_reference").equals(evidence.path("admissionReference").asText())
                    || !instant(rs, "checked_at").equals(Instant.parse(evidence.path("admissionCheckedAt").asText()))
                    || !instant(rs, "expires_at").equals(Instant.parse(evidence.path("admissionExpiresAt").asText())))
                return null;
            var reviewed = instant(rs, "reviewed_at");
            if (!reviewed.equals(Instant.parse(evidence.path("reviewedAt").asText()))
                    || !evidence.path("documentSha256").asText().matches("[a-f0-9]{64}")) return null;
            String title = evidence.path("title").asText();
            String precision = evidence.path("precision").asText();
            var day = LocalDate.parse(evidence.path("publishedOn").asText());
            if (title.isBlank() || title.length() > 500 || day.isAfter(now.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate()))
                return null;
            Instant published = null;
            if ("INSTANT".equals(precision)) {
                published = Instant.parse(evidence.path("publishedAt").asText());
                if (published.isAfter(reviewed) || !day.equals(published.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate()))
                    return null;
            } else if (!"DATE".equals(precision) || evidence.has("publishedAt")) return null;
            return new Item(page.getHost(), title, url, published, day, precision, reviewed);
        } catch (RuntimeException invalidEvidence) {
            return null; // Never expose malformed evidence or promote a search claim as a source timestamp.
        }
    }
}
