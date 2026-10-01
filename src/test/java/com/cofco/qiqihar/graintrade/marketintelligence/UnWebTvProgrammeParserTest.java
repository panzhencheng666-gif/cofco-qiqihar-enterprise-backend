package com.cofco.qiqihar.graintrade.marketintelligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;

class UnWebTvProgrammeParserTest {
    private static final String CARD = """
            <div class="un3-card-row-video">
              <div class="mediaun-timezone">2026-09-28T13:00:00-04:00</div>
              <a class="ajax-popup-link" href="/en/asset/k1k/k1kfcwu5kc">
                <img src="//cfvod.kaltura.com/p/2503451/sp/250345100/thumbnail/entry_id/1_kfcwu5kc/version/1/width/564">
                <div class="card-img-overlay"><span class="badge">Live</span></div>
              </a>
              <h4><a href="/en/asset/k1k/k1kfcwu5kc">General Debate &amp; Assembly</a></h4>
            </div>
            """;

    @Test void bindsCardUrlToSameCardProviderEntry() throws Exception {
        var event = UnWebTvProgrammeParser.parse(CARD).getFirst();
        assertThat(event.title()).isEqualTo("General Debate & Assembly");
        assertThat(event.url()).isEqualTo("https://webtv.un.org/en/asset/k1k/k1kfcwu5kc");
        assertThat(event.entryId()).isEqualTo("1_kfcwu5kc");
        assertThat(event.sourceReportsLive()).isTrue();
        assertThat(event.startsAt()).isEqualTo(java.time.Instant.parse("2026-09-28T13:00:00Z"));
    }

    @Test void doesNotInferLiveFromTitleOrTime() throws Exception {
        var event = UnWebTvProgrammeParser.parse(CARD.replace("<span class=\"badge\">Live</span>", "")
                .replace("General Debate &amp; Assembly", "LIVE event at current time")).getFirst();
        assertThat(event.sourceReportsLive()).isFalse();
    }

    @Test void rejectsMismatchedEntryProviderAndHost() {
        for (String value : new String[]{CARD.replace("1_kfcwu5kc/version", "1_abcdefgh/version"),
                CARD.replace("/p/2503451/", "/p/1234/"), CARD.replace("cfvod.kaltura.com", "evil.example"),
                CARD.replace("href=\"/en/asset", "href=\"https://evil.example/en/asset")}) {
            assertThatThrownBy(() -> UnWebTvProgrammeParser.parse(value)).isInstanceOf(java.io.IOException.class);
        }
    }

    @Test void rejectsEmptyOversizedAndConflictingDuplicateCards() {
        for (String value : new String[]{"<html>empty</html>", "x".repeat(2_000_001),
                CARD + CARD.replace("General Debate &amp; Assembly", "different programme")}) {
            assertThatThrownBy(() -> UnWebTvProgrammeParser.parse(value)).isInstanceOf(java.io.IOException.class);
        }
    }

    @Test void deduplicatesIdenticalCards() throws Exception {
        assertThat(UnWebTvProgrammeParser.parse(CARD + CARD)).hasSize(1);
    }

    @Test void rejectsMissingOrInvalidSourceTimestamp() {
        for (String value : new String[]{CARD.replace("mediaun-timezone", "unknown"),
                CARD.replace("2026-09-28T13:00:00-04:00", "2026-02-30T13:00:00-04:00")}) {
            assertThatThrownBy(() -> UnWebTvProgrammeParser.parse(value)).isInstanceOf(java.io.IOException.class);
        }
    }

    @Test void associatesOnlyUniqueExactTitleAndInstant() throws Exception {
        var programme = UnWebTvProgrammeParser.parse(CARD).getFirst();
        var start = java.time.Instant.parse("2026-09-28T13:00:00Z");
        var event = new UnWebTvScheduleParser.Event("TU1", " General   Debate & Assembly ", "unused", start,
                start.plusSeconds(3600), null, false);
        assertThat(UnWebTvProgrammeParser.match(programme, java.util.List.of(event))).contains(event);
        assertThat(UnWebTvProgrammeParser.match(programme, java.util.List.of(event, event))).isEmpty();
        assertThat(UnWebTvProgrammeParser.match(programme, java.util.List.of(new UnWebTvScheduleParser.Event(
                "TU2", event.title(), event.url(), start.plusSeconds(1), event.endsAt(), null, false)))).isEmpty();
        assertThat(UnWebTvProgrammeParser.match(programme, java.util.List.of(new UnWebTvScheduleParser.Event(
                "TU2", "General Debate", event.url(), start, event.endsAt(), null, false)))).isEmpty();
        assertThat(UnWebTvProgrammeParser.match(programme, java.util.List.of(new UnWebTvScheduleParser.Event(
                "TU2", event.title(), event.url(), start, event.endsAt(), null, true)))).isEmpty();
    }
}
