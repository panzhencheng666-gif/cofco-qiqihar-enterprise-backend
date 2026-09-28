package com.cofco.qiqihar.graintrade.marketintelligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class FaoMarketVideoFeedTest {
    @Test
    void keepsSourceDateAndOfficialPlaybackPage() throws Exception {
        var html = """
                <div class="d-list d-list-video"><div class="d-list-visual"></div>
                  <div class="d-list-content"><h5 class="title-link">
                    <a href="https://www.fao.org/markets-and-trade/news-and-events/multimedia/video-detail/soco-2026/en" class="title-link">SOCO 2026</a>
                  </h5><h6 class="date">09/07/2026</h6></div></div>
                <div class="d-list d-list-video"><div class="d-list-visual"></div>
                  <div class="d-list-content"><h5 class="title-link">
                    <a href="https://evil.example/video" class="title-link">Untrusted</a>
                  </h5><h6 class="date">09/07/2026</h6></div></div>
                """;
        var videos = FaoMarketVideoFeed.parse(html, Instant.parse("2026-09-27T00:00:00Z"));
        assertThat(videos).hasSize(1);
        assertThat(videos.getFirst().publishedOn()).isEqualTo(LocalDate.of(2026, 7, 9));
        assertThat(videos.getFirst().url()).contains("www.fao.org/markets-and-trade/");
    }

    @Test
    void rejectsUndatedVideoCards() {
        assertThatThrownBy(() -> FaoMarketVideoFeed.parse(
                "<div class=\"d-list d-list-video\"><a href=\"https://www.fao.org/\">No date</a></div></div>",
                Instant.now())).hasMessageContaining("no dated video links");
    }
}
