package com.cofco.qiqihar.graintrade.marketintelligence;

import static org.assertj.core.api.Assertions.*;
import com.cofco.qiqihar.graintrade.testsupport.ProtectedTestDatabase;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;

class NewsDiscoveryReadTest {
    static final Instant NOW = NewsCandidateReviewTest.NOW;
    JdbcTemplate sql;
    NewsDiscoveryRead read;
    @BeforeEach void setup() {
        var ds = ProtectedTestDatabase.shared().dataSource();
        sql = new JdbcTemplate(ds);
        sql.execute("CREATE SCHEMA IF NOT EXISTS market_intelligence");
        for (String table : List.of("news_discovery_candidate", "news_discovery_source_admission", "news_discovery_search_schedule"))
            sql.execute("DROP TABLE IF EXISTS market_intelligence." + table);
        for (String file : List.of("candidate", "admission", "search-schedule"))
            new ResourceDatabasePopulator(new ClassPathResource("db/news-discovery/" + file + "-schema.sql")).execute(ds);
        read = new NewsDiscoveryRead(JdbcClient.create(ds));
    }
    void verified(String html) {
        var repo = new NewsDiscoveryRepository(JdbcClient.create(sql.getDataSource()));
        var candidate = NewsCandidateReviewTest.candidate();
        repo.save("CNLiteBasic", "grain", List.of(new NewsSearchResults.Candidate(
            java.net.URI.create(candidate.url()), candidate.title(), candidate.claimedDate(), candidate.lastDiscoveredAt())));
        assertThat(repo.review(repo.pending(1).getFirst(), NewsCandidateReviewTest.evaluate(html), NOW)).isTrue();
        var admission = NewsCandidateReviewTest.admission();
        sql.update("INSERT INTO market_intelligence.news_discovery_source_admission VALUES (?,?,?,?,true,true)",
            "https://news.example/", admission.reference(),
            admission.checkedAt().atOffset(ZoneOffset.UTC), admission.expiresAt().atOffset(ZoneOffset.UTC));
    }
    NewsDiscoveryRead.Snapshot snapshot() { return read.snapshot(true, NOW.plusSeconds(60), NOW); }
    @Test void disabledNeverNeedsTables() {
        sql.execute("DROP TABLE market_intelligence.news_discovery_candidate");
        assertThat(read.snapshot(false, null, NOW).state()).isEqualTo("DISABLED");
    }
    @Test void missingTableIsUnavailableNotEmptySuccess() {
        sql.execute("DROP TABLE market_intelligence.news_discovery_candidate");
        assertThat(snapshot().state()).isEqualTo("UNAVAILABLE");
        assertThat(snapshot().items()).isEmpty();
    }
    @Test void persistedProviderFailureAndNoRunRemainDistinct() {
        assertThat(snapshot().searchState()).isEqualTo("NOT_RUN");
        sql.update("UPDATE market_intelligence.news_discovery_search_schedule SET last_state='FAILED', last_reason='PROVIDER_UNAVAILABLE',last_completed_at=?",
            NOW.minusSeconds(10).atOffset(ZoneOffset.UTC));
        assertThat(snapshot().searchState()).isEqualTo("FAILED");
        assertThat(snapshot().lastSearchCompletedAt()).isEqualTo(NOW.minusSeconds(10));
    }
    @Test void returnsReviewedTitleAndDateWithoutInventingMidnight() {
        verified(NewsCandidateReviewTest.HTML);
        assertThat(snapshot().items()).singleElement().satisfies(item -> {
            assertThat(item.title()).isEqualTo("Wheat harvest update");
            assertThat(item.publicationPrecision()).isEqualTo("DATE");
            assertThat(item.publishedAt()).isNull();
            assertThat(item.publishedOn()).isEqualTo(LocalDate.parse("2026-09-28"));
        });
    }
    @Test void instantUsesBeijingDateAndSurvivesReopening() {
        verified(NewsCandidateReviewTest.HTML.replace("2026-09-28", "2026-09-27T18:00:00Z"));
        read = new NewsDiscoveryRead(JdbcClient.create(sql.getDataSource()));
        assertThat(snapshot().items()).singleElement().satisfies(item -> {
            assertThat(item.publishedAt()).isEqualTo(Instant.parse("2026-09-27T18:00:00Z"));
            assertThat(item.publishedOn()).isEqualTo(LocalDate.parse("2026-09-28"));
        });
    }
    @Test void revokedExpiredOrReplacedAdmissionHidesPreviouslyVerifiedItems() {
        verified(NewsCandidateReviewTest.HTML);
        assertThat(snapshot().items()).hasSize(1);
        assertThat(read.snapshot(true, NOW.plusSeconds(500), NOW.plusSeconds(121)).items()).isEmpty();
        sql.update("UPDATE market_intelligence.news_discovery_source_admission SET metadata_display_allowed=false");
        assertThat(snapshot().items()).isEmpty();
        sql.update("UPDATE market_intelligence.news_discovery_source_admission SET metadata_display_allowed=true,review_reference='different'");
        assertThat(snapshot().items()).isEmpty();
    }
    @Test void corruptEvidenceOrPendingRowsAreNeverPublished() {
        verified(NewsCandidateReviewTest.HTML);
        assertThat(snapshot().items()).hasSize(1);
        sql.update("UPDATE market_intelligence.news_discovery_candidate SET review_evidence=review_evidence || '{\"publishedOn\":\"bad\"}'::jsonb");
        assertThat(snapshot().items()).isEmpty();
        sql.update("UPDATE market_intelligence.news_discovery_candidate SET review_state='PENDING_VERIFICATION'");
        assertThat(snapshot().items()).isEmpty();
    }
    @Test void expiredSearchWindowIsNotActiveButRetainsAdmittedHistory() {
        verified(NewsCandidateReviewTest.HTML);
        var result = read.snapshot(true, NOW, NOW);
        assertThat(result.state()).isEqualTo("EXPIRED");
        assertThat(result.items()).hasSize(1);
    }
    @Test void httpDisabledIsNoStoreAndDoesNotNeedTables() throws Exception {
        sql.execute("DROP TABLE market_intelligence.news_discovery_candidate");
        var controller = new NewsDiscoveryController(JdbcClient.create(sql.getDataSource()), new org.springframework.mock.env.MockEnvironment());
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/market-intelligence/news/discovery"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control", "no-store"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.state").value("DISABLED"));
    }
    @Test void httpMissingSchemaIs503WithoutSqlDetails() throws Exception {
        sql.execute("DROP TABLE market_intelligence.news_discovery_candidate");
        var env = new org.springframework.mock.env.MockEnvironment()
            .withProperty("qiqihar.market-intelligence.discovery.enabled", "true")
            .withProperty("qiqihar.market-intelligence.discovery.deadline", "2099-01-01T00:00:00Z");
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
            new NewsDiscoveryController(JdbcClient.create(sql.getDataSource()), env)).build();
        var result = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/market-intelligence/news/discovery"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isServiceUnavailable())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control", "no-store"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.state").value("UNAVAILABLE")).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("SELECT", "news_discovery_candidate", "Exception");
    }
}
