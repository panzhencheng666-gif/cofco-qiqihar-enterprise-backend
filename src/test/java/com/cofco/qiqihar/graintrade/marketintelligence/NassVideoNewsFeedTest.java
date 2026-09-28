package com.cofco.qiqihar.graintrade.marketintelligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class NassVideoNewsFeedTest {
    @Test
    void acceptsOnlyDatedVideoLinksPublishedByOfficialIndex() throws Exception {
        var html = """
                <p>02/13/24 &nbsp;<a target="_blank" href="https://www.youtube.com/live/0EY87thoLuo?si=abc">2022 Census of Agriculture Data Release Event</a></p>
                <p>02/13/24 &nbsp;<a href="https://evil.example/watch?v=0EY87thoLuo">Untrusted</a></p>
                <p><a href="https://youtu.be/8t_ZfpMtjzg">Undated featured video</a></p>
                """;
        var items = NassVideoNewsFeed.parse(html, Instant.parse("2026-09-27T00:00:00Z"));
        assertThat(items).hasSize(1);
        assertThat(items.getFirst().publishedOn()).isEqualTo(LocalDate.of(2024, 2, 13));
        assertThat(items.getFirst().url()).isEqualTo("https://www.youtube.com/watch?v=0EY87thoLuo");
    }

    @Test
    void doesNotInventDatesForUndatedVideoItems() {
        assertThatThrownBy(() -> NassVideoNewsFeed.parse(
                "<p><a href=\"https://youtu.be/8t_ZfpMtjzg\">Featured</a></p>", Instant.now()))
                .hasMessageContaining("no dated official video links");
    }
}
