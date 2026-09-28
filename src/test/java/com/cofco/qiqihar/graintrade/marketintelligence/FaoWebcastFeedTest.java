package com.cofco.qiqihar.graintrade.marketintelligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class FaoWebcastFeedTest {
    @Test
    void convertsOfficialRomeBroadcastTimeAndKeepsEventLink() throws Exception {
        var html = """
                <div class="d-list d-list-video d-list-webcast"><div class="d-list-visual"></div>
                  <div class="d-list-content"><h5 class="title-link">
                    <a href="https://www.fao.org/webcast/detail/grain-market-event/en">Grain market event</a>
                  </h5><h6 class="date-location d-list-date-location"><span class="date">25 Sep 2026, 11:30</span></h6>
                  </div></div>
                <div class="d-list d-list-video d-list-webcast"><div class="d-list-visual"></div>
                  <div class="d-list-content"><h5 class="title-link">
                    <a href="https://evil.example/webcast/detail/fake/en">Fake event</a>
                  </h5><span class="date">25 Sep 2026, 11:30</span></div></div>
                """;
        var events = FaoWebcastFeed.parse(html, Instant.parse("2026-09-27T00:00:00Z"));
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().startsAt()).isEqualTo(Instant.parse("2026-09-25T09:30:00Z"));
    }

    @Test
    void rejectsUndatedOrNonOfficialEvents() {
        assertThatThrownBy(() -> FaoWebcastFeed.parse("<div>no events</div>", Instant.now()))
                .hasMessageContaining("no dated official events");
    }
}
