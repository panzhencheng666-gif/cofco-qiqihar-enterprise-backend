package com.cofco.qiqihar.graintrade.marketintelligence;

import static org.assertj.core.api.Assertions.*;
import com.cofco.qiqihar.graintrade.testsupport.ProtectedTestDatabase;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import javax.sql.DataSource;

class NewsDiscoveryQueueTest {
    static final Instant NOW = Instant.parse("2026-09-28T16:00:00Z");
    DataSource ds;
    JdbcTemplate sql;
    NewsDiscoveryRepository repo;
    NewsDiscoveryQueue queue;
    @BeforeEach void setup() {
        ds = ProtectedTestDatabase.shared().dataSource();
        sql = new JdbcTemplate(ds);
        sql.execute("CREATE SCHEMA IF NOT EXISTS market_intelligence");
        sql.execute("DROP TABLE IF EXISTS market_intelligence.news_discovery_host_schedule");
        sql.execute("DROP TABLE IF EXISTS market_intelligence.news_discovery_candidate");
        new ResourceDatabasePopulator(new ClassPathResource("db/news-discovery/candidate-schema.sql")).execute(ds);
        new ResourceDatabasePopulator(new ClassPathResource("db/news-discovery/schedule-schema.sql")).execute(ds);
        repo = new NewsDiscoveryRepository(JdbcClient.create(ds));
        queue = new NewsDiscoveryQueue(ds);
        save("https://news.example/grain", NOW.minusSeconds(60));
    }
    void save(String url, Instant at) {
        repo.save("CNLiteBasic", "grain", List.of(new NewsSearchResults.Candidate(
            URI.create(url), "Wheat harvest", "2026-09-28", at)));
    }
    NewsCandidateReview.Decision pending(NewsDiscoveryQueue.Lease lease) {
        return new NewsCandidateReview.Decision("PENDING_VERIFICATION", "FETCH_NOT_READY",
            Map.of("articleUrl", lease.candidate().url()));
    }
    NewsDiscoveryQueue.Lease requireClaim(Instant time) {
        var result = queue.claim(time);
        assertThat(result).isPresent();
        return result.orElseThrow();
    }
    @Test void sameHostIsLeasedOnceAndSurvivesNewQueueInstance() {
        save("https://news.example/second", NOW.minusSeconds(60));
        var lease = queue.claim(NOW);
        assertThat(lease).isPresent();
        assertThat(new NewsDiscoveryQueue(ds).claim(NOW.plusSeconds(1))).isEmpty();
        save("https://another.example/grain", NOW.minusSeconds(60));
        assertThat(queue.claim(NOW.plusSeconds(1))).get().extracting(NewsDiscoveryQueue.Lease::host)
            .isEqualTo("another.example");
    }
    @Test void concurrentWorkersCannotDoubleClaimHost() throws Exception {
        try (var workers = Executors.newFixedThreadPool(8)) {
            var jobs = new java.util.ArrayList<java.util.concurrent.Callable<Boolean>>();
            for (int i=0;i<16;i++) jobs.add(() -> new NewsDiscoveryQueue(ds).claim(NOW).isPresent());
            int acquired=0;
            for (var result : workers.invokeAll(jobs)) if (result.get()) acquired++;
            assertThat(acquired).isEqualTo(1);
        }
    }
    @Test void retryAfterAndCrawlDelaySurviveRestartAndRediscovery() {
        var lease = requireClaim(NOW);
        assertThat(queue.complete(lease, pending(lease), NOW.plusSeconds(1), NOW.plusSeconds(600), 900_000)).isTrue();
        save("https://news.example/grain", NOW.plusSeconds(5));
        assertThat(new NewsDiscoveryQueue(ds).claim(NOW.plusSeconds(900))).isEmpty();
        assertThat(new NewsDiscoveryQueue(ds).claim(NOW.plusSeconds(902))).isPresent();
    }
    @Test void expiredWorkerCannotCompleteAfterLeaseReassignment() {
        var old = requireClaim(NOW);
        assertThat(queue.claim(NOW.plusSeconds(119))).isEmpty();
        var fresh = requireClaim(NOW.plusSeconds(121));
        assertThat(fresh.token()).isNotEqualTo(old.token());
        assertThat(queue.complete(old, pending(old), NOW.plusSeconds(122), null, 0)).isFalse();
        assertThat(queue.complete(fresh, pending(fresh), NOW.plusSeconds(122), null, 0)).isTrue();
    }
    @Test void repeatedFailureBackoffIncreases() {
        var first = requireClaim(NOW);
        assertThat(queue.complete(first, pending(first), NOW.plusSeconds(1), null, 0)).isTrue();
        assertThat(queue.claim(NOW.plusSeconds(60))).isEmpty();
        var second = requireClaim(NOW.plusSeconds(61));
        assertThat(queue.complete(second, pending(second), NOW.plusSeconds(62), null, 0)).isTrue();
        assertThat(new NewsDiscoveryQueue(ds).claim(NOW.plusSeconds(181))).isEmpty();
        assertThat(new NewsDiscoveryQueue(ds).claim(NOW.plusSeconds(182))).isPresent();
    }
    @Test void scheduleWriteFailureRollsBackCandidateReview() {
        var lease = requireClaim(NOW);
        sql.execute("ALTER TABLE market_intelligence.news_discovery_host_schedule ADD CONSTRAINT force_failure CHECK (failures=0)");
        assertThatThrownBy(() -> queue.complete(lease, pending(lease), NOW.plusSeconds(1), null, 0))
            .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(sql.queryForObject("SELECT reviewed_at IS NULL FROM market_intelligence.news_discovery_candidate", Boolean.class))
            .isTrue();
        sql.execute("ALTER TABLE market_intelligence.news_discovery_host_schedule DROP CONSTRAINT force_failure");
        assertThat(queue.complete(lease, pending(lease), NOW.plusSeconds(2), null, 0)).isTrue();
    }
    @Test void unrepresentablyLongRetryAfterCreatesDurableHold() {
        var lease = requireClaim(NOW);
        assertThatCode(() -> queue.complete(lease,pending(lease),NOW.plusSeconds(1),Instant.MAX,Long.MAX_VALUE))
            .doesNotThrowAnyException();
        assertThat(new NewsDiscoveryQueue(ds).claim(NOW.plusSeconds(100_000_000))).isEmpty();
        assertThat(sql.queryForObject("SELECT next_attempt_at::text FROM market_intelligence.news_discovery_host_schedule",String.class))
            .isEqualTo("infinity");
    }
    @Test void verifiedCandidateStopsRetryingAndReleasesHostForOtherArticles() {
        var lease = requireClaim(NOW);
        var verified = NewsCandidateReviewTest.evaluate(NewsCandidateReviewTest.HTML);
        assertThat(queue.complete(lease,verified,NOW.plusSeconds(1),null,0)).isTrue();
        assertThat(queue.claim(NOW.plusSeconds(500))).isEmpty();
        save("https://news.example/second",NOW.plusSeconds(502));
        assertThat(queue.claim(NOW.plusSeconds(503))).isPresent();
    }
}
