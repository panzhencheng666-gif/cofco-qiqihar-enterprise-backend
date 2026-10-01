package com.cofco.qiqihar.graintrade.marketintelligence;

import static org.assertj.core.api.Assertions.*;
import com.cofco.qiqihar.graintrade.testsupport.ProtectedTestDatabase;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class NewsDiscoveryScheduleTest {
    DataSource ds;
    JdbcTemplate sql;
    NewsDiscoveryWorkerTest.TestClock clock;
    List<String> calls;
    AtomicInteger workers;
    @BeforeEach void setup() {
        ds=ProtectedTestDatabase.shared().dataSource();
        sql=new JdbcTemplate(ds);
        sql.execute("CREATE SCHEMA IF NOT EXISTS market_intelligence");
        sql.execute("DROP TABLE IF EXISTS market_intelligence.news_discovery_search_schedule");
        sql.execute("DROP TABLE IF EXISTS market_intelligence.news_discovery_candidate");
        new ResourceDatabasePopulator(new ClassPathResource("db/news-discovery/candidate-schema.sql")).execute(ds);
        new ResourceDatabasePopulator(new ClassPathResource("db/news-discovery/search-schedule-schema.sql")).execute(ds);
        clock=new NewsDiscoveryWorkerTest.TestClock();
        calls=new ArrayList<>();
        workers=new AtomicInteger();
    }
    NewsSearchResults.Result found(String engine,String query) {
        calls.add(engine);
        return new NewsSearchResults.Result(NewsSearchResults.State.CANDIDATES,List.of(
            new NewsSearchResults.Candidate(NewsCandidateReviewTest.PAGE,"Wheat harvest",null,clock.instant())),0,0,"CANDIDATES");
    }
    NewsDiscoverySchedule schedule() {
        return new NewsDiscoverySchedule(ds,clock,NewsDiscoveryWorkerTest.START.plusSeconds(3600),this::found,
            () -> {workers.incrementAndGet();return "IDLE";});
    }
    @Test void searchPersistsCandidatesAndRestartKeepsNextSearchTime() {
        assertThat(schedule().cycle()).isEqualTo("OK");
        assertThat(calls).containsExactly("CNLiteBasic");
        assertThat(sql.queryForObject("SELECT count(*) FROM market_intelligence.news_discovery_candidate",Integer.class)).isEqualTo(1);
        clock.now=clock.now.plusSeconds(60);
        assertThat(schedule().cycle()).isEqualTo("OK");
        assertThat(calls).hasSize(1);
        clock.now=NewsDiscoveryWorkerTest.START.plusSeconds(300);
        assertThat(schedule().cycle()).isEqualTo("OK");
        assertThat(calls).containsExactly("CNLiteBasic","GlobalAdvanced");
        assertThat(workers).hasValue(3);
        assertThat(sql.queryForObject("SELECT count(*) FROM market_intelligence.news_discovery_candidate",Integer.class)).isEqualTo(1);
    }
    @Test void deadlineStopsSearchAndWorker() {
        clock.now=NewsDiscoveryWorkerTest.START.plusSeconds(3600);
        assertThat(schedule().cycle()).isEqualTo("CLOSED");
        assertThat(calls).isEmpty();
        assertThat(workers).hasValue(0);
    }
    @Test void providerFailureIsDurableAndDoesNotRetryImmediately() {
        var runtime=new NewsDiscoverySchedule(ds,clock,clock.now.plusSeconds(3600),(engine,query) ->
            new NewsSearchResults.Result(NewsSearchResults.State.FAILED,List.of(),0,0,"SEARCH_UNAVAILABLE"),
            () -> {workers.incrementAndGet();return "IDLE";});
        assertThat(runtime.cycle()).isEqualTo("OK");
        assertThat(sql.queryForObject("SELECT last_state FROM market_intelligence.news_discovery_search_schedule",String.class))
            .isEqualTo("FAILED");
        clock.now=clock.now.plusSeconds(60);
        assertThat(schedule().cycle()).isEqualTo("OK");
        assertThat(calls).isEmpty();
        assertThat(workers).hasValue(2);
    }
    @Test void concurrentTickReturnsBusyInsteadOfRunningTwoWorkers() throws Exception {
        var started=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        var runtime=new NewsDiscoverySchedule(ds,clock,clock.now.plusSeconds(3600),this::found,() -> {
            started.countDown();
            try { release.await(2,TimeUnit.SECONDS); } catch(InterruptedException e){Thread.currentThread().interrupt();}
            return "IDLE";
        });
        try(var pool=Executors.newSingleThreadExecutor()) {
            var first=pool.submit(runtime::cycle);
            try {
                assertThat(started.await(2,TimeUnit.SECONDS)).isTrue();
                assertThat(runtime.cycle()).isEqualTo("BUSY");
            } finally {release.countDown();}
            assertThat(first.get()).isEqualTo("OK");
        }
    }
    @Test void failedCandidateSaveDoesNotWriteFalseSearchSuccess() {
        var runtime=new NewsDiscoverySchedule(ds,clock,clock.now.plusSeconds(3600),(engine,query) ->
            new NewsSearchResults.Result(NewsSearchResults.State.CANDIDATES,List.of(
                new NewsSearchResults.Candidate(java.net.URI.create("https://127.0.0.1/"),"invalid",null,clock.now)),0,0,"CANDIDATES"),
            () -> "IDLE");
        assertThat(runtime.cycle()).isEqualTo("UNAVAILABLE");
        assertThat(sql.queryForObject("SELECT last_state FROM market_intelligence.news_discovery_search_schedule",String.class))
            .isNotEqualTo("CANDIDATES");
        clock.now=clock.now.plusSeconds(60);
        assertThat(schedule().cycle()).isEqualTo("OK");
        assertThat(calls).isEmpty();
    }
}
