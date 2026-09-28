package com.cofco.qiqihar.graintrade.marketintelligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class EiaTodayInEnergyFeedTest {
    @Test
    void keepsOfficialPublicationInstantAndRejectsOtherHosts() throws Exception {
        var xml = """
                <rss><channel>
                  <item><title>Energy market update</title><link>https://www.eia.gov/todayinenergy/detail.php?id=68204</link><pubDate>Fri, 25 Sep 2026  09:00:00 EST</pubDate></item>
                  <item><title>Bad host</title><link>https://other.example/todayinenergy/detail.php?id=1</link><pubDate>Fri, 25 Sep 2026 09:00:00 EST</pubDate></item>
                </channel></rss>
                """;
        var items = EiaTodayInEnergyFeed.parse(xml.getBytes(StandardCharsets.UTF_8),
                Instant.parse("2026-09-27T00:00:00Z"));
        assertThat(items).hasSize(1);
        assertThat(items.getFirst().publishedAt()).isEqualTo(Instant.parse("2026-09-25T14:00:00Z"));
    }

    @Test
    void rejectsDtdAndMissingValidItems() {
        var malicious = "<!DOCTYPE rss [<!ENTITY xxe SYSTEM 'file:///etc/passwd'>]><rss><channel><item><title>&xxe;</title></item></channel></rss>";
        assertThatThrownBy(() -> EiaTodayInEnergyFeed.parse(malicious.getBytes(StandardCharsets.UTF_8),
                Instant.now())).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> EiaTodayInEnergyFeed.parse(
                "<rss><channel/></rss>".getBytes(StandardCharsets.UTF_8), Instant.now()))
                .hasMessageContaining("no valid dated headlines");
    }
}
