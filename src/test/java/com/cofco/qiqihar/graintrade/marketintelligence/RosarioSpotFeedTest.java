package com.cofco.qiqihar.graintrade.marketintelligence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class RosarioSpotFeedTest {
    private static final Instant FETCHED = Instant.parse("2026-09-27T17:58:27Z");
    private static final String RESPONSE = """
            {"meta":{"fuente":"granos.ar","generado_en":"2026-09-27T17:58:27.280Z"},
             "data":{"fecha":"2026-09-24","fuente_precios":"Consiagro / BCR Rosario",
              "granos":{"soja":{"ars_tn":562000},"maiz":{"ars_tn":296000},
                        "trigo":{"ars_tn":351200},"sorgo":{"ars_tn":287000},
                        "girasol":{"ars_tn":762800}}}}
            """;

    @Test
    void sourceDateIsPreservedAndNeverReplacedByFetchTime() {
        var snapshot = RosarioSpotFeed.parse(new ObjectMapper(), RESPONSE, FETCHED, FETCHED);
        assertEquals("PUBLISHED_DATA", snapshot.status());
        assertEquals("2026-09-24", snapshot.sourceDate().toString());
        assertEquals(FETCHED, snapshot.fetchedAt());
        assertEquals(5, snapshot.observations().size());
        assertEquals("玉米", snapshot.observations().get(1).name());
        assertEquals("296000", snapshot.observations().get(1).arsPerTonne().toPlainString());
        assertEquals("ARS/吨", snapshot.unit());
        assertNull(snapshot.lastError());
    }

    @Test
    void rejectsWrongSourceAndIncompleteOrFutureDatedPrices() {
        var mapper = new ObjectMapper();
        assertThrows(IllegalArgumentException.class,
                () -> RosarioSpotFeed.parse(mapper, RESPONSE.replace("granos.ar", "other"), FETCHED, FETCHED));
        assertThrows(IllegalArgumentException.class,
                () -> RosarioSpotFeed.parse(mapper, RESPONSE.replace("\"ars_tn\":296000", "\"ars_tn\":0"), FETCHED, FETCHED));
        assertThrows(IllegalArgumentException.class,
                () -> RosarioSpotFeed.parse(mapper, RESPONSE.replace("2026-09-24", "2026-09-29"), FETCHED, FETCHED));
    }

    @Test
    void disabledFeedDoesNotClaimCurrentQuotes() {
        var feed = new RosarioSpotFeed(new ObjectMapper(), null, null, false);
        feed.refresh();
        var snapshot = feed.overview().data();
        assertEquals("DISABLED", snapshot.status());
        assertTrue(snapshot.observations().isEmpty());
        assertNull(snapshot.sourceDate());
    }
}
