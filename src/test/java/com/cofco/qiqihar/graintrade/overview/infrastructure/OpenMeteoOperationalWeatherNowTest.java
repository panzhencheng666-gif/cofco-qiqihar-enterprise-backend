package com.cofco.qiqihar.graintrade.overview.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class OpenMeteoOperationalWeatherNowTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void parsesSelectedTownshipWeatherAndLiveVisualInputs() throws Exception {
        var coordinate = new OpenMeteoOperationalWeatherNow.RegionCoordinate(
                "230200", "230229101", "古城镇", "TOWNSHIP", "TOWNSHIP",
                new BigDecimal("123.45"), new BigDecimal("46.78"));
        var result = OpenMeteoOperationalWeatherNow.parse(coordinate, json.readTree("""
                {"current":{"time":"2026-09-19T10:15","temperature_2m":12.4,
                "precipitation":3.2,"soil_moisture_0_to_1cm":0.31,"weather_code":61,
                "cloud_cover":88,"wind_speed_10m":16.2,"wind_direction_10m":145}}
                """), Instant.parse("2026-09-19T02:16:00Z"));

        assertThat(result.rootRegionCode()).isEqualTo("230200");
        assertThat(result.regionCode()).isEqualTo("230229101");
        assertThat(result.regionName()).isEqualTo("古城镇");
        assertThat(result.weatherCode()).isEqualTo(61);
        assertThat(result.cloudCoverPercent()).isEqualByComparingTo("88");
        assertThat(result.observationPrecision()).isEqualTo("TOWNSHIP");
    }

    @Test
    void labelsVillageRequestsAsInheritedTownshipObservations() throws Exception {
        var coordinate = new OpenMeteoOperationalWeatherNow.RegionCoordinate(
                "230200", "230229101", "古城镇", "TOWNSHIP", "VILLAGE",
                new BigDecimal("123.45"), new BigDecimal("46.78"));
        var result = OpenMeteoOperationalWeatherNow.parse(coordinate, json.readTree("""
                {"current":{"time":"2026-09-19T10:15","temperature_2m":12.4,
                "precipitation":0,"soil_moisture_0_to_1cm":0.31,"weather_code":2,
                "cloud_cover":66,"wind_speed_10m":8,"wind_direction_10m":90}}
                """), Instant.parse("2026-09-19T02:16:00Z"));

        assertThat(result.observationPrecision()).isEqualTo("INHERITED_TOWNSHIP");
    }
}
