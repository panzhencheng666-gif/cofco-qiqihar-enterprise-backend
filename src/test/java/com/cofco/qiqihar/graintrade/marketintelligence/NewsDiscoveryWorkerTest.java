package com.cofco.qiqihar.graintrade.marketintelligence;

import static org.assertj.core.api.Assertions.*;
import com.cofco.qiqihar.graintrade.testsupport.ProtectedTestDatabase;
import java.time.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;

class NewsDiscoveryWorkerTest {
    static final Instant START=Instant.parse("2026-09-28T16:00:00Z");
    DataSource ds;
    JdbcTemplate sql;
    TestClock clock;
    List<URI> requests;
    NewsDiscoveryWorker worker;
    int robotsStatus=200,articleStatus=200;
    String rules="User-agent: *\nAllow: /\nCrawl-delay: 2\n";
    String redirect;
    static class TestClock extends Clock {
        Instant now=START;
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return now;}
    }
    @BeforeEach void setup() {
        ds=ProtectedTestDatabase.shared().dataSource();
        sql=new JdbcTemplate(ds);
        sql.execute("CREATE SCHEMA IF NOT EXISTS market_intelligence");
        sql.execute("DROP TABLE IF EXISTS market_intelligence.news_discovery_source_admission");
        sql.execute("DROP TABLE IF EXISTS market_intelligence.news_discovery_host_schedule");
        sql.execute("DROP TABLE IF EXISTS market_intelligence.news_discovery_candidate");
        new ResourceDatabasePopulator(new ClassPathResource("db/news-discovery/candidate-schema.sql"),
            new ClassPathResource("db/news-discovery/schedule-schema.sql")).execute(ds);
        new ResourceDatabasePopulator(new ClassPathResource("db/news-discovery/admission-schema.sql")).execute(ds);
        clock=new TestClock();
        requests=new ArrayList<>();
        new NewsDiscoveryRepository(JdbcClient.create(ds)).save("CNLiteBasic","grain",List.of(
            new NewsSearchResults.Candidate(NewsCandidateReviewTest.PAGE,"Search wheat","2020-01-01",START.minusSeconds(60))));
        worker=createWorker();
    }
    NewsDiscoveryWorker createWorker() {
        return new NewsDiscoveryWorker(ds,clock,NewsSourceFetchTest.DNS,(target,remaining) -> {
            requests.add(target.uri());
            boolean robots=target.uri().getPath().equals("/robots.txt");
            return new NewsPinnedHttp.Response(robots?robotsStatus:articleStatus,robots?null:redirect,
                robots?"text/plain":"text/html; charset=utf-8",
                (robots?rules:NewsCandidateReviewTest.HTML).getBytes(StandardCharsets.UTF_8),
                robotsStatus==429 || articleStatus==429 || articleStatus==302?Map.of("retry-after","600"):Map.of());
        });
    }
    void admit() {
        // Production code never writes this admin-controlled fixture.
        sql.update("""
            INSERT INTO market_intelligence.news_discovery_source_admission
                (origin_uri,review_reference,checked_at,expires_at,fetch_allowed,metadata_display_allowed)
            VALUES ('https://news.example/','test-fixture-only',?::timestamptz,?::timestamptz,true,true)
            """,START.minusSeconds(60).toString(),START.plusSeconds(3600).toString());
    }
    @Test void noAdmissionDoesNotFetchAndPersistsReason() {
        assertThat(worker.runOnce()).isEqualTo("DEFERRED");
        assertThat(requests).isEmpty();
        assertThat(sql.queryForObject("SELECT review_reason FROM market_intelligence.news_discovery_candidate",String.class))
            .isEqualTo("SOURCE_ADMISSION_REQUIRED");
    }
    @Test void twoCyclesReachVerifiedCandidateThroughRealComponentsAndDatabase() {
        admit();
        assertThat(worker.runOnce()).isEqualTo("DEFERRED");
        assertThat(requests).containsExactly(URI.create("https://news.example/robots.txt"));
        assertThat(worker.runOnce()).isEqualTo("IDLE");
        clock.now=START.plusSeconds(61);
        assertThat(worker.runOnce()).isEqualTo("VERIFIED");
        assertThat(requests).hasSize(2);
        assertThat(sql.queryForObject("SELECT review_evidence->>'publishedOn' FROM market_intelligence.news_discovery_candidate",String.class))
            .isEqualTo("2026-09-28");
        assertThat(createWorker().runOnce()).isEqualTo("IDLE");
    }
    @Test void robotsDeniedNeverFetchesArticle() {
        admit(); rules="User-agent: *\nDisallow: /\n";
        assertThat(worker.runOnce()).isEqualTo("DEFERRED");
        clock.now=START.plusSeconds(61);
        assertThat(worker.runOnce()).isEqualTo("IDLE");
        assertThat(requests).hasSize(1);
        assertThat(sql.queryForObject("SELECT review_reason FROM market_intelligence.news_discovery_candidate",String.class))
            .isEqualTo("ROBOTS_DISALLOWED");
    }
    @Test void robotsRetryAfterSurvivesWorkerRestart() {
        admit(); robotsStatus=429;
        assertThat(worker.runOnce()).isEqualTo("DEFERRED");
        clock.now=START.plusSeconds(61);
        assertThat(createWorker().runOnce()).isEqualTo("IDLE");
        assertThat(requests).hasSize(1);
    }
    @Test void articleRetryAfterSurvivesWorkerRestart() {
        admit(); articleStatus=429;
        assertThat(worker.runOnce()).isEqualTo("DEFERRED");
        clock.now=START.plusSeconds(61);
        assertThat(worker.runOnce()).isEqualTo("DEFERRED");
        clock.now=START.plusSeconds(200);
        assertThat(createWorker().runOnce()).isEqualTo("IDLE");
        assertThat(requests).hasSize(2);
    }
    @Test void revocationBetweenCyclesStopsArticleFetch() {
        admit();
        assertThat(worker.runOnce()).isEqualTo("DEFERRED");
        sql.update("UPDATE market_intelligence.news_discovery_source_admission SET fetch_allowed=false");
        clock.now=START.plusSeconds(61);
        assertThat(worker.runOnce()).isEqualTo("DEFERRED");
        assertThat(requests).hasSize(1);
    }
    @Test void redirectsDoNotBypassPerHostScheduling() {
        admit(); articleStatus=302; redirect="https://another.example/grain";
        assertThat(worker.runOnce()).isEqualTo("DEFERRED");
        clock.now=START.plusSeconds(61);
        assertThat(worker.runOnce()).isEqualTo("DEFERRED");
        assertThat(requests).hasSize(2).allMatch(uri -> uri.getHost().equals("news.example"));
        clock.now=START.plusSeconds(200);
        assertThat(createWorker().runOnce()).isEqualTo("IDLE");
    }
}
