package com.cofco.qiqihar.graintrade.regionalproduction.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RegionalPublicDataRefreshWorkerTest {
    @Test
    void readsOpenMeteoCurrentValuesUsedByTheDailyWeatherModel() {
        String json = """
                {"current":{"time":"2026-09-14T10:15","temperature_2m":18.4,
                "precipitation":1.2,"soil_moisture_0_to_1cm":0.27}}
                """;
        assertThat(RegionalPublicDataRefreshWorker.number(json, "temperature_2m"))
                .isEqualByComparingTo("18.4");
        assertThat(RegionalPublicDataRefreshWorker.number(json, "soil_moisture_0_to_1cm"))
                .isEqualByComparingTo("0.27");
    }
}
