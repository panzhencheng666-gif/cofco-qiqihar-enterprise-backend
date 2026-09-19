package com.cofco.qiqihar.graintrade.overview.infrastructure;

import com.cofco.qiqihar.graintrade.overview.application.OperationalSituationCatalogue;
import com.cofco.qiqihar.graintrade.overview.application.OperationalWeatherNow;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class OpenMeteoOperationalWeatherNow implements OperationalWeatherNow {
    private static final Duration TTL = Duration.ofMinutes(15);
    private static final ZoneId CHINA = ZoneId.of("Asia/Shanghai");
    private static final String CURRENT = String.join(",",
            "temperature_2m", "precipitation", "soil_moisture_0_to_1cm", "weather_code",
            "cloud_cover", "wind_speed_10m", "wind_direction_10m");
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final HttpClient http;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    @Autowired
    public OpenMeteoOperationalWeatherNow(JdbcClient jdbc, ObjectMapper json) {
        this(jdbc, json, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).build());
    }

    OpenMeteoOperationalWeatherNow(JdbcClient jdbc, ObjectMapper json, HttpClient http) {
        this.jdbc = jdbc;
        this.json = json;
        this.http = http;
    }

    @Override
    public synchronized Optional<OperationalSituationCatalogue.WeatherObservation> forRegion(
            String requestedRegionCode) {
        RegionCoordinate coordinate = coordinate(requestedRegionCode).orElse(null);
        if (coordinate == null) return Optional.empty();
        Instant now = Instant.now();
        Cached cached = cache.get(coordinate.regionCode());
        if (cached != null && cached.fetchedAt().plus(TTL).isAfter(now)) {
            return Optional.of(cached.observation());
        }
        try {
            var request = HttpRequest.newBuilder(weatherUri(coordinate))
                    .timeout(Duration.ofSeconds(12))
                    .header("User-Agent", "COFCO-Qiqihar-OperationalWeather/1.0 (+cached-region-selection)")
                    .GET().build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new IllegalStateException("Open-Meteo HTTP " + response.statusCode());
            var observation = parse(coordinate, json.readTree(response.body()), now);
            cache.put(coordinate.regionCode(), new Cached(observation, now));
            return Optional.of(observation);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return cached == null ? Optional.empty() : Optional.of(cached.observation());
        } catch (Exception exception) {
            return cached == null ? Optional.empty() : Optional.of(cached.observation());
        }
    }

    private Optional<RegionCoordinate> coordinate(String requestedRegionCode) {
        return jdbc.sql("""
                WITH RECURSIVE lineage AS (
                  SELECT code,parent_code,name,level,0 AS depth
                  FROM platform.region WHERE code=:code
                  UNION ALL
                  SELECT parent.code,parent.parent_code,parent.name,parent.level,lineage.depth+1
                  FROM lineage JOIN platform.region parent ON parent.code=lineage.parent_code
                ), selected AS (
                  SELECT level FROM lineage WHERE depth=0
                ), root AS (
                  SELECT code FROM lineage WHERE level='PREFECTURE' LIMIT 1
                )
                SELECT candidate.code,candidate.name,candidate.level,
                       (SELECT level FROM selected) AS requested_level,root.code AS root_code,
                       ST_X(ST_PointOnSurface(boundary.geometry)) AS longitude,
                       ST_Y(ST_PointOnSurface(boundary.geometry)) AS latitude
                FROM lineage candidate
                JOIN overview.administrative_boundary boundary ON boundary.region_code=candidate.code
                CROSS JOIN root
                WHERE root.code IN ('230200','231100','150700','232700')
                ORDER BY CASE
                    WHEN (SELECT level FROM selected)='VILLAGE' AND candidate.level='TOWNSHIP' THEN 0
                    WHEN (SELECT level FROM selected)<>'VILLAGE' AND candidate.depth=0 THEN 0
                    ELSE 1 END,
                  candidate.depth
                LIMIT 1
                """).param("code", requestedRegionCode)
                .query((row, index) -> new RegionCoordinate(
                        row.getString("root_code"), row.getString("code"), row.getString("name"),
                        row.getString("level"), row.getString("requested_level"),
                        row.getBigDecimal("longitude"),
                        row.getBigDecimal("latitude")))
                .optional();
    }

    static OperationalSituationCatalogue.WeatherObservation parse(
            RegionCoordinate coordinate, JsonNode root, Instant fetchedAt) {
        JsonNode current = root.path("current");
        BigDecimal temperature = decimal(current, "temperature_2m");
        BigDecimal precipitation = decimal(current, "precipitation");
        BigDecimal soil = decimal(current, "soil_moisture_0_to_1cm")
                .multiply(new BigDecimal("100")).setScale(1, RoundingMode.HALF_UP);
        int weatherCode = current.path("weather_code").asInt();
        BigDecimal windSpeed = decimal(current, "wind_speed_10m");
        BigDecimal windDirection = decimal(current, "wind_direction_10m");
        BigDecimal cloudCover = decimal(current, "cloud_cover");
        Instant observedAt = LocalDateTime.parse(current.path("time").asString())
                .atZone(CHINA).toInstant();
        String risk = risk(weatherCode, temperature, precipitation, soil, windSpeed);
        String assessment = "气温" + temperature + "℃，降水" + precipitation + "毫米，风速"
                + windSpeed + "公里/小时，云量" + cloudCover + "% ，表层土壤含水率约" + soil
                + "%；" + risk;
        String precision = "VILLAGE".equals(coordinate.requestedLevel())
                ? "INHERITED_TOWNSHIP" : coordinate.level();
        return new OperationalSituationCatalogue.WeatherObservation(
                coordinate.rootRegionCode(), coordinate.regionCode(), coordinate.regionName(),
                coordinate.longitude(), coordinate.latitude(), observedAt, temperature, precipitation,
                soil, weatherCode, windSpeed, windDirection, cloudCover, precision, risk, assessment,
                "Open-Meteo", weatherUri(coordinate).toString(), fetchedAt);
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        if (!node.has(field) || !node.path(field).isNumber())
            throw new IllegalArgumentException("weather field missing: " + field);
        return node.path(field).decimalValue();
    }

    private static String risk(int code, BigDecimal temperature, BigDecimal precipitation,
            BigDecimal soil, BigDecimal windSpeed) {
        if (code >= 95) return "强对流天气，关注田间作业和运输安全";
        if (windSpeed.compareTo(new BigDecimal("39")) >= 0) return "风力偏强，关注运输与仓储安全";
        if (temperature.compareTo(BigDecimal.ZERO) < 0) return "低温提示，关注冻害和运输条件";
        if (precipitation.compareTo(new BigDecimal("10")) >= 0) return "降水偏强，关注渍涝风险";
        if (soil.compareTo(new BigDecimal("15")) < 0) return "表层墒情偏低，关注干旱风险";
        return "当前未触发系统气象关注阈值";
    }

    private static URI weatherUri(RegionCoordinate coordinate) {
        return URI.create("https://api.open-meteo.com/v1/forecast?latitude="
                + coordinate.latitude().toPlainString() + "&longitude="
                + coordinate.longitude().toPlainString() + "&current=" + CURRENT
                + "&timezone=Asia%2FShanghai");
    }

    record RegionCoordinate(String rootRegionCode, String regionCode, String regionName,
            String level, String requestedLevel, BigDecimal longitude, BigDecimal latitude) {}
    private record Cached(OperationalSituationCatalogue.WeatherObservation observation,
            Instant fetchedAt) {}
}
