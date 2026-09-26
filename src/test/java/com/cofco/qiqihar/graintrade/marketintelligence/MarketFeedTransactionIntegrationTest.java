package com.cofco.qiqihar.graintrade.marketintelligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cofco.qiqihar.graintrade.testsupport.ProtectedTestDatabase;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class MarketFeedTransactionIntegrationTest {
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;

    @Configuration
    @EnableTransactionManagement
    static class Transactions { }

    enum Source {
        FAO("fao-newsroom-rss", FaoNewsFeed.class, 1),
        DEPARTMENT("moa-department-news", MoaDepartmentNewsFeed.class, 1),
        MONITOR("moa-public-monitor", MoaPublicMarketFeed.class, 3);

        final String code;
        final Class<?> type;
        final int requests;
        Source(String code, Class<?> type, int requests) {
            this.code = code;
            this.type = type;
            this.requests = requests;
        }
    }

    @BeforeAll
    static void migrate() {
        ProtectedTestDatabase.shared().flyway().migrate();
    }

    @BeforeEach
    void setUp() {
        var dataSource = ProtectedTestDatabase.shared().dataSource();
        jdbc = new JdbcTemplate(dataSource);
        clearFixtures();
        context = new AnnotationConfigApplicationContext();
        context.register(Transactions.class);
        context.registerBean(JdbcTemplate.class, () -> jdbc);
        context.registerBean(JdbcClient.class, () -> JdbcClient.create(dataSource));
        context.registerBean(ObjectMapper.class, () -> JsonMapper.builder().build());
        context.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(dataSource));
        context.register(FaoNewsFeed.class, MoaDepartmentNewsFeed.class, MoaPublicMarketFeed.class);
        context.refresh();
    }

    @AfterEach
    void tearDown() {
        if (context != null) context.close();
        if (jdbc != null) clearFixtures();
    }

    private void clearFixtures() {
        jdbc.execute("DROP TRIGGER IF EXISTS test_reject_feed_success ON market_intelligence.source_sync_state");
        jdbc.execute("DROP FUNCTION IF EXISTS market_intelligence.test_reject_success()");
        jdbc.execute("DELETE FROM market_intelligence.news_headline WHERE source_code IN ('fao-newsroom-rss','moa-department-news','moa-public-monitor')");
        jdbc.execute("DELETE FROM market_intelligence.source_sync_state WHERE source_code IN ('fao-newsroom-rss','moa-department-news','moa-public-monitor')");
        jdbc.execute("DELETE FROM market_intelligence.china_daily_index WHERE series_code='grain' AND period=DATE '2026-09-24'");
    }

    @ParameterizedTest
    @EnumSource(Source.class)
    void failedRefreshRollsBackAllWritesAndPreservesLastSuccessfulSnapshot(Source source) throws Exception {
        var bean = context.getBean(source.type);
        var requests = new AtomicInteger();
        var revision = new AtomicInteger();
        var http = mock(HttpClient.class);
        when(http.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any()))
                .thenAnswer(call -> {
                    requests.incrementAndGet();
                    HttpRequest request = call.getArgument(0);
                    @SuppressWarnings("unchecked")
                    HttpResponse<InputStream> response = mock(HttpResponse.class);
                    when(response.statusCode()).thenReturn(200);
                    when(response.uri()).thenReturn(request.uri());
                    when(response.body()).thenReturn(new ByteArrayInputStream(
                            fixture(source, request, revision.get()).getBytes(StandardCharsets.UTF_8)));
                    return response;
                });
        Object target = AopTestUtils.getTargetObject(bean);
        ReflectionTestUtils.setField(target, "http", http);

        rejectSuccess(source);
        refresh(bean);
        assertThat(requests.get()).isEqualTo(source.requests);
        assertThat(state(source, "last_error")).isEqualTo("DataIntegrityViolationException");
        assertThat(state(source, "last_success_at")).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM market_intelligence.news_headline WHERE source_code=?", Integer.class, source.code))
                .as("headlines must roll back when success-state write fails").isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM market_intelligence.china_daily_index WHERE series_code='grain' AND period=DATE '2026-09-24'", Integer.class))
                .as("indices must roll back with the headlines").isZero();

        jdbc.execute("DROP TRIGGER test_reject_feed_success ON market_intelligence.source_sync_state");
        refresh(bean);
        assertThat(requests.get()).isEqualTo(source.requests * 2);
        var successfulAt = state(source, "last_success_at");
        assertThat(successfulAt).isNotNull();
        assertThat(state(source, "last_error")).isNull();
        var title = jdbc.queryForObject("SELECT title FROM market_intelligence.news_headline WHERE source_code=?", String.class, source.code);
        assertThat(title).contains("revision-0");

        revision.incrementAndGet();
        rejectSuccess(source);
        refresh(bean);
        assertThat(requests.get()).isEqualTo(source.requests * 3);
        assertThat(state(source, "last_error")).isEqualTo("DataIntegrityViolationException");
        assertThat(state(source, "last_success_at")).isEqualTo(successfulAt);
        assertThat(jdbc.queryForObject("SELECT title FROM market_intelligence.news_headline WHERE source_code=?", String.class, source.code))
                .as("failed refresh must preserve the prior headline").isEqualTo(title);
        if (source == Source.MONITOR) {
            assertThat(jdbc.queryForObject("SELECT value FROM market_intelligence.china_daily_index WHERE series_code='grain' AND period=DATE '2026-09-24'", java.math.BigDecimal.class))
                    .isEqualByComparingTo("109.74");
        }
    }

    private Object state(Source source, String field) {
        return jdbc.queryForObject("SELECT " + field + " FROM market_intelligence.source_sync_state WHERE source_code=?", Object.class, source.code);
    }

    private void rejectSuccess(Source source) {
        // Reject only success writes so the catch block can still record the failure after rollback.
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION market_intelligence.test_reject_success() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                  IF NEW.last_error IS NULL THEN RAISE check_violation USING MESSAGE='injected feed success failure'; END IF;
                  RETURN NEW;
                END $$
                """);
        jdbc.execute("DROP TRIGGER IF EXISTS test_reject_feed_success ON market_intelligence.source_sync_state");
        jdbc.execute("CREATE TRIGGER test_reject_feed_success BEFORE INSERT OR UPDATE ON market_intelligence.source_sync_state FOR EACH ROW WHEN (NEW.source_code='" + source.code + "') EXECUTE FUNCTION market_intelligence.test_reject_success()");
    }

    private static void refresh(Object bean) {
        if (bean instanceof FaoNewsFeed feed) feed.refresh();
        else if (bean instanceof MoaDepartmentNewsFeed feed) feed.refresh();
        else ((MoaPublicMarketFeed) bean).refresh();
    }

    private static String fixture(Source source, HttpRequest request, int revision) {
        if (source == Source.FAO) return """
                <rss><channel><item><title>Food supply revision-%d</title>
                <link>https://www.fao.org/newsroom/detail/transaction-fixture/en</link>
                <pubDate>Wed, 23 Sep 2026 12:00:00 GMT</pubDate></item></channel></rss>
                """.formatted(revision);
        if (source == Source.DEPARTMENT) return """
                <li class="ztlb"><a href="./202609/t20260923_1.htm" title='秋粮收购 revision-%d'>秋粮</a><span>2026-09-23</span></li>
                """.formatted(revision);
        if (request.uri().equals(MoaPublicMarketFeed.INDEX_CHART)) return "token:'test-fixture'";
        if (request.uri().equals(MoaPublicMarketFeed.INDEX_DATA)) return """
                {"content":[{"publishDate":"2026-09-24","indexData":[{"indexName":"粮食价格指数","indexValue":%s}]}]}
                """.formatted(revision == 0 ? "109.74" : "110.74");
        return """
                <li><a href="./202609/t20260924_1.htm" title='粮价监测 revision-%d'><span class="sj_gztzri">2026-09-24</span></a></li>
                """.formatted(revision);
    }
}
