package com.cofco.qiqihar.graintrade.overview.interfaceadapter;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
        classes = GrainTradeApplication.class,
        properties = {
            "qiqihar.regional-public-data.enabled=false",
            "qiqihar.public-situation.enabled=false"
        })
@AutoConfigureMockMvc
@UsesProtectedTestDatabase
class OperationalSituationRestIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void seedPublicEventSnapshot() {
        jdbc.sql("DELETE FROM overview.public_event_snapshot WHERE source_code='NASA_EONET'").update();
        jdbc.sql("""
                INSERT INTO overview.public_event_snapshot(
                  source_code,event_id,title,category_code,category_label,longitude,latitude,
                  observed_at,event_url,evidence_url,fetched_at)
                VALUES('NASA_EONET','EONET_TEST','公开事件','severeStorms','Severe Storms',
                  125.1,47.2,:observed,'https://eonet.gsfc.nasa.gov/api/v3/events/EONET_TEST',
                  'https://example.test/evidence',:fetched)
                """).param("observed", java.sql.Timestamp.from(Instant.parse("2026-09-18T00:00:00Z")))
                .param("fetched", java.sql.Timestamp.from(Instant.now())).update();
        jdbc.sql("""
                UPDATE overview.public_event_refresh_state
                SET last_attempt_at=now(),last_success_at=now(),last_error=NULL,record_count=1
                WHERE source_code='NASA_EONET'
                """).update();
    }

    @Test
    void returnsSourceAwarePublicEventsAndWeatherWithoutInventedValues() throws Exception {
        mvc.perform(get("/api/v1/overview/operational-situation")
                        .principal(() -> "production-tester"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.publicEvents[0].eventId").value("EONET_TEST"))
                .andExpect(jsonPath("$.data.publicEvents[0].eventUrl").isNotEmpty())
                .andExpect(jsonPath("$.data.policyEvents").isArray())
                .andExpect(jsonPath("$.data.sources[?(@.code == 'NASA_EONET')].status")
                        .value("READY"))
                .andExpect(jsonPath("$.data.sources[?(@.code == 'OPEN_METEO')].notice")
                        .isNotEmpty());
    }

    @Test
    void rejectsUnknownParameters() throws Exception {
        mvc.perform(get("/api/v1/overview/operational-situation")
                        .principal(() -> "production-tester")
                        .queryParam("unexpected", "value"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_OPERATIONAL_SITUATION_QUERY"));
    }

    @Test
    void rejectsMalformedRegionCodesBeforeWeatherLookup() throws Exception {
        mvc.perform(get("/api/v1/overview/operational-situation")
                        .principal(() -> "production-tester")
                        .queryParam("regionCode", "not-a-region"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_OPERATIONAL_SITUATION_QUERY"));
    }
}
