package com.cofco.qiqihar.graintrade.marketintelligence;

import static org.assertj.core.api.Assertions.assertThat;

import com.cofco.qiqihar.graintrade.testsupport.ProtectedTestDatabase;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class OfficialNewsPersistenceIntegrationTest {
    @Test
    void requeryReturnsSavedOfficialNewsAndVideoLinksWithSourceDatePrecision() {
        var dataSource = ProtectedTestDatabase.shared().dataSource();
        var template = new JdbcTemplate(dataSource);
        template.execute("CREATE SCHEMA market_intelligence");
        template.execute("""
                CREATE TABLE market_intelligence.news_headline (
                    source_code varchar(40) NOT NULL, article_url text NOT NULL, title text NOT NULL,
                    published_at timestamptz NOT NULL, fetched_at timestamptz NOT NULL,
                    PRIMARY KEY (source_code, article_url))
                """);
        template.execute("""
                CREATE TABLE market_intelligence.source_sync_state (
                    source_code varchar(40) PRIMARY KEY, last_attempt_at timestamptz,
                    last_success_at timestamptz, latest_period date, last_error varchar(400))
                """);
        template.execute("""
                CREATE TABLE market_intelligence.webcast_event (
                    source_code varchar(40) NOT NULL, event_url text NOT NULL, title text NOT NULL,
                    starts_at timestamptz NOT NULL, fetched_at timestamptz NOT NULL,
                    PRIMARY KEY (source_code, event_url))
                """);
        var jdbc = JdbcClient.create(dataSource);
        var transactions = new DataSourceTransactionManager(dataSource);
        var fetchedAt = Instant.parse("2026-09-27T17:00:00Z");
        new EiaTodayInEnergyFeed(jdbc, template, transactions).save(List.of(
                new EiaTodayInEnergyFeed.Headline("Energy update",
                        "https://www.eia.gov/todayinenergy/detail.php?id=68204",
                        Instant.parse("2026-09-27T16:30:00Z"))), fetchedAt);
        new FaoNewsFeed(jdbc, template, transactions).save(List.of(
                new FaoNewsFeed.Headline("Food supply update",
                        "https://www.fao.org/newsroom/detail/food-supply/en",
                        Instant.parse("2026-09-25T12:00:00Z"), fetchedAt)), fetchedAt);
        new FaoMarketVideoFeed(jdbc, template, transactions).save(List.of(
                new FaoMarketVideoFeed.Video("Market video",
                        "https://www.fao.org/markets-and-trade/news-and-events/multimedia/video-detail/soco-2026/en",
                        LocalDate.parse("2026-07-09"))), fetchedAt);
        new NassVideoNewsFeed(jdbc, template, transactions).save(List.of(
                new NassVideoNewsFeed.Video("Census release event",
                        "https://www.youtube.com/watch?v=0EY87thoLuo",
                        LocalDate.parse("2024-02-13"))), fetchedAt);
        new FaoWebcastFeed(jdbc, template, transactions).save(List.of(
                new FaoWebcastFeed.Event("Grain market webcast",
                        "https://www.fao.org/webcast/detail/grain-market-event/en",
                        Instant.parse("2026-09-25T09:30:00Z"))), fetchedAt);

        var news = new MarketNewsController(jdbc).latest().data();
        assertThat(news).hasSize(2);
        assertThat(news).filteredOn(item -> item.sourceCode().equals("eia-today-in-energy"))
                .singleElement().satisfies(item -> {
            assertThat(item.sourceCode()).isEqualTo("eia-today-in-energy");
            assertThat(item.publishedOn()).isEqualTo(LocalDate.parse("2026-09-28"));
            assertThat(item.publicationPrecision()).isEqualTo("instant");
        });
        assertThat(news).filteredOn(item -> item.sourceCode().equals("fao-newsroom-rss"))
                .singleElement().satisfies(item -> {
                    assertThat(item.publishedOn()).isEqualTo(LocalDate.parse("2026-09-25"));
                    assertThat(item.publicationPrecision()).isEqualTo("date");
                });
        var videos = new NassVideoNewsFeed.Controller(jdbc).list().data();
        assertThat(videos).hasSize(2);
        assertThat(videos).extracting(NassVideoNewsFeed.VideoItem::url)
                .contains("https://www.youtube.com/watch?v=0EY87thoLuo");
        assertThat(videos).extracting(NassVideoNewsFeed.VideoItem::sourceName)
                .contains("FAO 市场与贸易", "USDA NASS");
        assertThat(new NassVideoNewsFeed.Controller(jdbc).list().data()).hasSize(2);
        assertThat(new FaoWebcastFeed.Controller(jdbc).list().data())
                .singleElement().satisfies(item -> {
                    assertThat(item.startsAt()).isEqualTo(Instant.parse("2026-09-25T09:30:00Z"));
                    assertThat(item.url()).contains("fao.org/webcast/detail/");
                });
    }
}
