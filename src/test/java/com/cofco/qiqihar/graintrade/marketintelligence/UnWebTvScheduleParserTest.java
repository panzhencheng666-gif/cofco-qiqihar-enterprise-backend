package com.cofco.qiqihar.graintrade.marketintelligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class UnWebTvScheduleParserTest {
    private static final String EVENT = """
            BEGIN:VEVENT
            UID:TU2174106835
            SUMMARY:Food Loss\\, Waste
            DTSTART;TZID=Europe/Rome:20260929T123000
            DTEND;TZID=Europe/Rome:20260929T140000
            LAST-MODIFIED:20260928T092037Z
            URL;VALUE=URI:https://teamup.com/ksf6ikyw6jst7sqii1/events/2174106835
            END:VEVENT
            """;

    private String calendar(String events) {
        return "BEGIN:VCALENDAR\nVERSION:2.0\n" + events + "END:VCALENDAR\n";
    }

    @Test void keepsIdentityAndSourceTimesWithoutInventingLiveState() throws Exception {
        var event = UnWebTvScheduleParser.parse(calendar(EVENT)).getFirst();
        assertThat(event.uid()).isEqualTo("TU2174106835");
        assertThat(event.title()).isEqualTo("Food Loss, Waste");
        assertThat(event.startsAt()).isEqualTo(Instant.parse("2026-09-29T10:30:00Z"));
        assertThat(event.endsAt()).isEqualTo(Instant.parse("2026-09-29T12:00:00Z"));
        assertThat(event.modifiedAt()).isEqualTo(Instant.parse("2026-09-28T09:20:37Z"));
        assertThat(event.cancelled()).isFalse();
    }

    @Test void unfoldsCrLfAndSupportsUtc() throws Exception {
        var source = EVENT.replace("Food Loss\\, Waste", "Food Loss\\,\n Waste")
                .replace(";TZID=Europe/Rome:20260929T123000", ":20260929T103000Z")
                .replace(";TZID=Europe/Rome:20260929T140000", ":20260929T120000Z");
        assertThat(UnWebTvScheduleParser.parse(calendar(source).replace("\n", "\r\n"))
                .getFirst().title()).isEqualTo("Food Loss,Waste");
    }

    @Test void retainsCancellationForLaterPersistence() throws Exception {
        var event = UnWebTvScheduleParser.parse(calendar(EVENT.replace("END:VEVENT", "STATUS:CANCELLED\nEND:VEVENT"))).getFirst();
        assertThat(event.cancelled()).isTrue();
    }

    @Test void leavesMissingOptionalModificationTimeUnknown() throws Exception {
        var source = EVENT.replace("LAST-MODIFIED:20260928T092037Z\n", "");
        assertThat(UnWebTvScheduleParser.parse(calendar(source)).getFirst().modifiedAt()).isNull();
    }

    @Test void refusesFloatingAllDayUnknownAndAmbiguousZoneTimes() {
        for (String start : new String[]{"DTSTART:20260929T123000", "DTSTART;VALUE=DATE:20260929",
                "DTSTART;TZID=Unknown/Zone:20260929T123000",
                "DTSTART;TZID=America/New_York:20261101T013000",
                "DTSTART;TZID=America/New_York:20260308T023000"}) {
            assertThatThrownBy(() -> UnWebTvScheduleParser.parse(calendar(EVENT.replace(
                    "DTSTART;TZID=Europe/Rome:20260929T123000", start)))).isInstanceOf(java.io.IOException.class);
        }
    }

    @Test void refusesInvalidDateAndReversedInterval() {
        for (String start : new String[]{"20260230T123000", "20260929T150000"}) {
            assertThatThrownBy(() -> UnWebTvScheduleParser.parse(calendar(EVENT.replace("20260929T123000", start))))
                    .isInstanceOf(java.io.IOException.class);
        }
    }

    @Test void rejectsDuplicateUidOrRequiredProperty() {
        assertThatThrownBy(() -> UnWebTvScheduleParser.parse(calendar(EVENT + EVENT))).isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(() -> UnWebTvScheduleParser.parse(calendar(EVENT.replace("UID:TU2174106835", "UID:TU2174106835\nUID:TU123"))))
                .isInstanceOf(java.io.IOException.class);
    }

    @Test void rejectsRecurrenceRatherThanSilentlyLosingOccurrences() {
        assertThatThrownBy(() -> UnWebTvScheduleParser.parse(calendar(EVENT.replace("END:VEVENT", "RRULE:FREQ=DAILY\nEND:VEVENT"))))
                .isInstanceOf(java.io.IOException.class);
    }

    @Test void rejectsExternalOrMismatchedEventUrl() {
        for (String url : new String[]{"https://evil.example/events/2174106835",
                "https://teamup.com/ksf6ikyw6jst7sqii1/events/123",
                "https://user@teamup.com/ksf6ikyw6jst7sqii1/events/2174106835"}) {
            assertThatThrownBy(() -> UnWebTvScheduleParser.parse(calendar(EVENT.replace(
                    "https://teamup.com/ksf6ikyw6jst7sqii1/events/2174106835", url))))
                    .isInstanceOf(java.io.IOException.class);
        }
    }

    @Test void rejectsTruncatedEmptyAndOversizedCalendar() {
        for (String source : new String[]{calendar(EVENT).replace("END:VCALENDAR", ""), calendar(""), "x".repeat(8_000_001)}) {
            assertThatThrownBy(() -> UnWebTvScheduleParser.parse(source)).isInstanceOf(java.io.IOException.class);
        }
    }

    @Test void ignoresNestedAlarmFieldsInsteadOfReplacingEventFields() throws Exception {
        var source = EVENT.replace("END:VEVENT", "BEGIN:VALARM\nSUMMARY:Alarm\nEND:VALARM\nEND:VEVENT");
        assertThat(UnWebTvScheduleParser.parse(calendar(source)).getFirst().title()).isEqualTo("Food Loss, Waste");
    }
}
