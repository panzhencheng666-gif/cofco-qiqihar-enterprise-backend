package com.cofco.qiqihar.graintrade.overview.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class EonetPublicEventFeedTest {
    @Test
    void keepsTheLatestPointGeometryAndPublicEvidenceOnly() {
        var payload = new ObjectMapper().readTree("""
                {"events":[{
                  "id":"EONET_1","title":"Typhoon Example","description":null,
                  "link":"https://eonet.gsfc.nasa.gov/api/v3/events/EONET_1",
                  "categories":[{"id":"severeStorms","title":"Severe Storms"}],
                  "sources":[{"id":"JTWC","url":"https://example.test/evidence"}],
                  "geometry":[
                    {"date":"2026-09-17T00:00:00Z","type":"Point","coordinates":[140,20]},
                    {"magnitudeValue":55,"magnitudeUnit":"kts","date":"2026-09-18T00:00:00Z","type":"Point","coordinates":[125,47]}
                  ]
                }]}
                """);

        var events = EonetPublicEventFeed.parse(payload);

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.eventId()).isEqualTo("EONET_1");
            assertThat(event.longitude()).isEqualByComparingTo("125");
            assertThat(event.latitude()).isEqualByComparingTo("47");
            assertThat(event.magnitudeValue()).isEqualByComparingTo("55");
            assertThat(event.evidenceUrl()).isEqualTo("https://example.test/evidence");
        });
    }
}
