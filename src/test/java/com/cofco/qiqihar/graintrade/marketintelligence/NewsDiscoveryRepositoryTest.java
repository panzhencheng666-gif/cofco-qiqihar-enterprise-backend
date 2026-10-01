package com.cofco.qiqihar.graintrade.marketintelligence;

import static org.assertj.core.api.Assertions.*;
import com.cofco.qiqihar.graintrade.testsupport.ProtectedTestDatabase;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;

class NewsDiscoveryRepositoryTest {
    JdbcTemplate sql;
    JdbcClient jdbc;
    static final Instant TIME = Instant.parse("2026-09-28T15:00:00Z");
    @BeforeEach void setup() {
        var ds = ProtectedTestDatabase.shared().dataSource();
        sql = new JdbcTemplate(ds);
        sql.execute("CREATE SCHEMA IF NOT EXISTS market_intelligence");
        sql.execute("DROP TABLE IF EXISTS market_intelligence.news_discovery_candidate");
        new ResourceDatabasePopulator(new ClassPathResource("db/news-discovery/candidate-schema.sql")).execute(ds);
        jdbc = JdbcClient.create(ds);
    }
    NewsSearchResults.Candidate item(Instant time) {
        return new NewsSearchResults.Candidate(URI.create("https://news.example/grain"), "Wheat harvest",
                "2026-09-27", time);
    }
    @Test void reviewPersistsEvidenceAndSurvivesRepositoryReopen() {
        var repo = new NewsDiscoveryRepository(jdbc);
        repo.save("CNLiteBasic", "wheat", List.of(item(TIME)));
        var candidate = repo.pending(1).getFirst();
        var decision = NewsCandidateReviewTest.evaluate(NewsCandidateReviewTest.HTML);
        assertThat(repo.review(candidate, decision, TIME.plusSeconds(3600))).isTrue();
        assertThat(new NewsDiscoveryRepository(jdbc).pending(10)).isEmpty();
        assertThat(sql.queryForObject("SELECT review_state FROM market_intelligence.news_discovery_candidate", String.class))
            .isEqualTo("VERIFIED");
        assertThat(sql.queryForObject("SELECT review_evidence->>'publishedOn' FROM market_intelligence.news_discovery_candidate", String.class))
            .isEqualTo("2026-09-28");
        assertThat(sql.queryForObject("SELECT review_evidence->>'documentSha256' FROM market_intelligence.news_discovery_candidate", String.class))
            .matches("[a-f0-9]{64}");
    }
    @Test void staleOrAlreadyFinalReviewCannotOverwriteCurrentCandidate() {
        var repo = new NewsDiscoveryRepository(jdbc);
        repo.save("CNLiteBasic", "wheat", List.of(item(TIME)));
        var stale = repo.pending(1).getFirst();
        repo.save("CNLiteBasic", "wheat", List.of(item(TIME.plusSeconds(10))));
        var decision = NewsCandidateReviewTest.evaluate(NewsCandidateReviewTest.HTML);
        assertThat(repo.review(stale, decision, TIME.plusSeconds(3600))).isFalse();
        var current = repo.pending(1).getFirst();
        assertThat(repo.review(current, decision, TIME.plusSeconds(3600))).isTrue();
        assertThat(repo.review(current, decision, TIME.plusSeconds(3601))).isFalse();
    }
    @Test void pendingReviewReasonIsDurableButNotPromoted() {
        var repo = new NewsDiscoveryRepository(jdbc);
        repo.save("CNLiteBasic", "wheat", List.of(item(TIME)));
        var decision = NewsCandidateReviewTest.evaluate(NewsCandidateReviewTest.HTML.replace("article:published_time", "article:modified_time"));
        assertThat(repo.review(repo.pending(1).getFirst(), decision, TIME.plusSeconds(3600))).isTrue();
        assertThat(new NewsDiscoveryRepository(jdbc).pending(1)).hasSize(1);
        assertThat(sql.queryForObject("SELECT review_reason FROM market_intelligence.news_discovery_candidate", String.class))
            .isEqualTo("MISSING_PUBLICATION");
    }
    @Test void persistsQuarantinedCandidateAndReopensWithoutPromotingClaimedTime() {
        new NewsDiscoveryRepository(jdbc).save("CNLiteBasic", "wheat", List.of(item(TIME)));
        var saved = new NewsDiscoveryRepository(jdbc).pending(10);
        assertThat(saved).singleElement().satisfies(candidate -> {
            assertThat(candidate.state()).isEqualTo("PENDING_VERIFICATION");
            assertThat(candidate.claimedDate()).isEqualTo("2026-09-27");
            assertThat(candidate.firstDiscoveredAt()).isEqualTo(TIME);
        });
    }
    @Test void repeatedAndOutOfOrderDiscoveryDeduplicatesAndPreservesReview() {
        var repo = new NewsDiscoveryRepository(jdbc);
        repo.save("CNLiteBasic", "wheat", List.of(item(TIME)));
        repo.save("GlobalAdvanced", "grain", List.of(item(TIME.plusSeconds(300))));
        repo.save("CNLiteBasic", "wheat", List.of(item(TIME.minusSeconds(100))));
        assertThat(repo.pending(10)).singleElement().satisfies(candidate -> {
            assertThat(candidate.firstDiscoveredAt()).isEqualTo(TIME.minusSeconds(100));
            assertThat(candidate.lastDiscoveredAt()).isEqualTo(TIME.plusSeconds(300));
        });
        sql.update("UPDATE market_intelligence.news_discovery_candidate SET review_state='REJECTED',review_reason='irrelevant'");
        repo.save("GlobalAdvanced", "grain", List.of(item(TIME.plusSeconds(600))));
        assertThat(repo.pending(10)).isEmpty();
        assertThat(sql.queryForObject("SELECT review_reason FROM market_intelligence.news_discovery_candidate", String.class)).isEqualTo("irrelevant");
    }
    @Test void sameBatchCanonicalDuplicatesKeepLatestMetadataAndUnknownDate() {
        var repo = new NewsDiscoveryRepository(jdbc);
        var newer = new NewsSearchResults.Candidate(URI.create("https://NEWS.example/grain#section"),
                "Updated harvest", null, TIME.plusSeconds(5));
        repo.save("GlobalAdvanced", "grain", List.of(item(TIME), newer));
        assertThat(repo.pending(10)).singleElement().satisfies(candidate -> {
            assertThat(candidate.url()).isEqualTo("https://news.example/grain");
            assertThat(candidate.title()).isEqualTo("Updated harvest");
            assertThat(candidate.claimedDate()).isEmpty();
            assertThat(candidate.firstDiscoveredAt()).isEqualTo(TIME);
            assertThat(candidate.lastDiscoveredAt()).isEqualTo(TIME.plusSeconds(5));
        });
    }
    @Test void invalidCandidateBatchWritesNothing() {
        var repo = new NewsDiscoveryRepository(jdbc);
        var bad = new NewsSearchResults.Candidate(URI.create("https://127.0.0.1/secret"), "bad", "", TIME);
        assertThatThrownBy(() -> repo.save("CNLiteBasic", "grain", List.of(item(TIME), bad)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(sql.queryForObject("SELECT count(*) FROM market_intelligence.news_discovery_candidate", Integer.class)).isZero();
        assertThatThrownBy(() -> repo.pending(101)).isInstanceOf(IllegalArgumentException.class);
    }
}
