package com.cofco.qiqihar.graintrade.marketintelligence;

import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.time.OffsetDateTime;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Candidate quarantine only: never writes published headlines or promotes source rights. */
final class NewsDiscoveryRepository {
    record Candidate(String url, String title, String claimedDate, Instant firstDiscoveredAt,
                     Instant lastDiscoveredAt, String state) {}
    private final JdbcClient jdbc;
    NewsDiscoveryRepository(JdbcClient jdbc) { this.jdbc = Objects.requireNonNull(jdbc); }

    boolean review(Candidate candidate, NewsCandidateReview.Decision decision, Instant reviewedAt) {
        Objects.requireNonNull(candidate);
        Objects.requireNonNull(decision);
        Objects.requireNonNull(reviewedAt);
        if (!List.of("PENDING_VERIFICATION", "VERIFIED").contains(decision.state())
                || decision.reason() == null || !decision.reason().matches("[A-Z_]{1,80}")
                || reviewedAt.isBefore(candidate.lastDiscoveredAt())
                || !candidate.url().equals(decision.evidence().get("articleUrl")))
            throw new IllegalArgumentException("Invalid review");
        String evidence = JsonMapper.builder().build().writeValueAsString(decision.evidence());
        if (evidence.length() > 16000) throw new IllegalArgumentException("Review evidence too large");
        return jdbc.sql("""
            UPDATE market_intelligence.news_discovery_candidate
            SET review_state=:state,review_reason=:reason,review_evidence=CAST(:evidence AS jsonb),reviewed_at=:reviewed
            WHERE article_url=:url AND review_state='PENDING_VERIFICATION'
              AND last_discovered_at=:discovered AND title=:title AND search_claimed_date=:claimed
              AND (reviewed_at IS NULL OR reviewed_at < :reviewed)
            """).param("state", decision.state()).param("reason", decision.reason()).param("evidence", evidence)
            .param("reviewed", reviewedAt.atOffset(java.time.ZoneOffset.UTC)).param("url", candidate.url())
            .param("discovered", candidate.lastDiscoveredAt().atOffset(java.time.ZoneOffset.UTC))
            .param("title", candidate.title()).param("claimed", candidate.claimedDate()).update() == 1;
    }

    void save(String engine, String query, List<NewsSearchResults.Candidate> candidates) {
        IqsSearchRequest.payload(engine, query);
        if (candidates == null || candidates.size() > 100) throw new IllegalArgumentException("Invalid candidate batch");
        var rows = new ArrayList<Map<String, String>>();
        for (var candidate : candidates) {
            if (candidate == null || candidate.url() == null || candidate.discoveredAt() == null
                    || candidate.title() == null || candidate.title().isBlank() || candidate.title().length() > 500
                    || (candidate.searchClaimedDate() != null && candidate.searchClaimedDate().length() > 100))
                throw new IllegalArgumentException("Invalid candidate");
            var url = NewsSearchResults.candidateUri(candidate.url().toString());
            if (url == null || url.toString().length() > 2048) throw new IllegalArgumentException("Invalid candidate URL");
            rows.add(Map.of("url", url.toString(), "host", url.getHost(), "title", candidate.title(),
                    "claimed", candidate.searchClaimedDate() == null ? "" : candidate.searchClaimedDate(),
                    "discovered", candidate.discoveredAt().toString()));
        }
        if (rows.isEmpty()) return;
        // One atomic statement for the whole batch; no partial writes if any row is invalid.
        jdbc.sql("""
            INSERT INTO market_intelligence.news_discovery_candidate AS saved
                (article_url,source_host,title,search_claimed_date,first_discovered_at,last_discovered_at,engine,query_text)
            SELECT DISTINCT ON (url) url,host,title,claimed,
                min(discovered) OVER (PARTITION BY url),discovered,:engine,:query
            FROM jsonb_to_recordset(CAST(:rows AS jsonb))
                AS input(url text,host text,title text,claimed text,discovered timestamptz)
            ORDER BY url,discovered DESC
            ON CONFLICT(article_url) DO UPDATE SET
                first_discovered_at=least(saved.first_discovered_at,excluded.first_discovered_at),
                last_discovered_at=greatest(saved.last_discovered_at,excluded.last_discovered_at),
                title=CASE WHEN excluded.last_discovered_at >= saved.last_discovered_at THEN excluded.title ELSE saved.title END,
                search_claimed_date=CASE WHEN excluded.last_discovered_at >= saved.last_discovered_at THEN excluded.search_claimed_date ELSE saved.search_claimed_date END,
                engine=CASE WHEN excluded.last_discovered_at >= saved.last_discovered_at THEN excluded.engine ELSE saved.engine END,
                query_text=CASE WHEN excluded.last_discovered_at >= saved.last_discovered_at THEN excluded.query_text ELSE saved.query_text END
            """).param("engine", engine).param("query", query)
                .param("rows", JsonMapper.builder().build().writeValueAsString(rows)).update();
    }

    List<Candidate> pending(int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid candidate limit");
        return jdbc.sql("""
            SELECT article_url,title,search_claimed_date,first_discovered_at,last_discovered_at,review_state
            FROM market_intelligence.news_discovery_candidate
            WHERE review_state='PENDING_VERIFICATION'
            ORDER BY last_discovered_at DESC,article_url LIMIT :limit
            """).param("limit", limit).query((rs, row) -> new Candidate(rs.getString("article_url"),
                rs.getString("title"), rs.getString("search_claimed_date"),
                rs.getObject("first_discovered_at", OffsetDateTime.class).toInstant(),
                rs.getObject("last_discovered_at", OffsetDateTime.class).toInstant(),
                rs.getString("review_state"))).list();
    }
}
