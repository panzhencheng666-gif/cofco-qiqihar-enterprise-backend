package com.cofco.qiqihar.graintrade.regionalproduction.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class JdbcRegionalPublicDataRepositoryTest {
    @Test
    void schedulesTheNextSuccessfulRefreshAt0830BeijingTime() {
        assertThat(JdbcRegionalPublicDataRepository.nextDailyRefresh(
                Instant.parse("2026-09-14T00:29:00Z")))
                .isEqualTo(Instant.parse("2026-09-14T00:30:00Z"));
        assertThat(JdbcRegionalPublicDataRepository.nextDailyRefresh(
                Instant.parse("2026-09-14T00:31:00Z")))
                .isEqualTo(Instant.parse("2026-09-15T00:30:00Z"));
    }

    @Test
    void weatherRefreshHasAFifteenMinuteFloor() {
        assertThat(JdbcRegionalPublicDataRepository.nextWeatherRefresh(
                Instant.parse("2026-09-18T06:00:00Z")))
                .isEqualTo(Instant.parse("2026-09-18T06:15:00Z"));
    }
}
