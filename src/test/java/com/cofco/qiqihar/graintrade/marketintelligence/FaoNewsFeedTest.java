package com.cofco.qiqihar.graintrade.marketintelligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class FaoNewsFeedTest {
    @Test
    void acceptsOnlyOfficialHttpsHeadlinesWithPublicationTime() throws Exception {
        var xml = """
                <rss><channel>
                  <item><title>Food supply update</title><link>https://www.fao.org/newsroom/detail/food/en</link><pubDate>Wed, 23 Sep 2026 12:00:00 Z</pubDate></item>
                  <item><title>Untrusted</title><link>https://fake-fao.org/story</link><pubDate>Wed, 23 Sep 2026 12:00:00 Z</pubDate></item>
                </channel></rss>
                """;
        var result = FaoNewsFeed.parse(xml.getBytes(StandardCharsets.UTF_8), Instant.parse("2026-09-24T00:00:00Z"));
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().title()).isEqualTo("Food supply update");
        assertThat(result.getFirst().publishedAt()).isEqualTo(Instant.parse("2026-09-23T12:00:00Z"));
    }

    @Test
    void rejectsDtdAndEmptyFeeds() {
        var payload = "<!DOCTYPE rss [<!ENTITY xxe SYSTEM 'file:///etc/passwd'>]><rss><channel><item><title>&xxe;</title></item></channel></rss>";
        assertThatThrownBy(() -> FaoNewsFeed.parse(payload.getBytes(StandardCharsets.UTF_8), Instant.now()))
                .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> FaoNewsFeed.parse("<rss><channel/></rss>".getBytes(StandardCharsets.UTF_8), Instant.now()))
                .hasMessageContaining("no valid headlines");
    }
}
