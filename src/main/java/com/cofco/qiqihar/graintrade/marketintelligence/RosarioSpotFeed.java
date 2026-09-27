package com.cofco.qiqihar.graintrade.marketintelligence;

import com.cofco.qiqihar.graintrade.shared.interfaceadapter.ApiResponse;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Source-published Rosario spot reference prices, never exchange realtime quotes. */
@RestController
public class RosarioSpotFeed {
    static final URI SOURCE = URI.create("https://granosar.lfcaucino.workers.dev/api/v1/pizarra");
    private static final ZoneId ARGENTINA = ZoneId.of("America/Argentina/Buenos_Aires");
    private static final Map<String, String> CROPS = Map.of(
            "soja", "大豆", "maiz", "玉米", "trigo", "小麦", "sorgo", "高粱", "girasol", "向日葵");

    public record Observation(String crop, String name, BigDecimal arsPerTonne) { }
    public record Snapshot(String status, LocalDate sourceDate, Instant fetchedAt,
                           Instant lastAttemptAt, String sourceName, String sourceUrl,
                           String attribution, String market, String unit,
                           List<Observation> observations, String lastError) { }

    private final ObjectMapper mapper;
    private final HttpClient http;
    private final Clock clock;
    private final boolean enabled;
    private volatile Snapshot snapshot = empty("WAITING_SOURCE", null, null);

    @Autowired
    public RosarioSpotFeed(ObjectMapper mapper,
            @Value("${qiqihar.market-intelligence.rosario-spot.enabled:true}") boolean enabled) {
        this(mapper, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(),
                Clock.systemUTC(), enabled);
    }

    RosarioSpotFeed(ObjectMapper mapper, HttpClient http, Clock clock, boolean enabled) {
        this.mapper = mapper;
        this.http = http;
        this.clock = clock;
        this.enabled = enabled;
        if (!enabled) snapshot = empty("DISABLED", null, null);
    }

    private static Snapshot empty(String status, Instant attempted, String error) {
        return new Snapshot(status, null, null, attempted, "granos.ar", SOURCE.toString(),
                "granos.ar · Rosario CAC/BCR", "阿根廷罗萨里奥现货参考价", "ARS/吨",
                List.of(), error);
    }

    static Snapshot parse(ObjectMapper mapper, String json, Instant fetchedAt, Instant attemptedAt) {
        JsonNode root = mapper.readTree(json);
        if (!"granos.ar".equals(root.path("meta").path("fuente").asText()))
            throw new IllegalArgumentException("ROSARIO_SOURCE_INVALID");
        JsonNode data = root.path("data");
        LocalDate sourceDate = LocalDate.parse(data.path("fecha").asText());
        if (sourceDate.isAfter(LocalDate.ofInstant(fetchedAt, ARGENTINA)))
            throw new IllegalArgumentException("ROSARIO_FUTURE_SOURCE_DATE");
        JsonNode prices = data.path("granos");
        var observations = new ArrayList<Observation>();
        for (String crop : List.of("soja", "maiz", "trigo", "sorgo", "girasol")) {
            JsonNode value = prices.path(crop).path("ars_tn");
            if (!value.isNumber() || value.decimalValue().signum() <= 0)
                throw new IllegalArgumentException("ROSARIO_PRICE_INVALID");
            observations.add(new Observation(crop, CROPS.get(crop), value.decimalValue()));
        }
        return new Snapshot("PUBLISHED_DATA", sourceDate, fetchedAt, attemptedAt,
                "granos.ar", SOURCE.toString(), "granos.ar · Rosario CAC/BCR",
                "阿根廷罗萨里奥现货参考价", "ARS/吨", List.copyOf(observations), null);
    }

    @Scheduled(initialDelayString = "${qiqihar.market-intelligence.rosario-spot.initial-delay:30s}",
            fixedDelayString = "${qiqihar.market-intelligence.rosario-spot.refresh-delay:10m}")
    public synchronized void refresh() {
        if (!enabled) return;
        Instant attemptedAt = clock.instant();
        try {
            HttpRequest request = HttpRequest.newBuilder(SOURCE).timeout(Duration.ofSeconds(8))
                    .header("Accept", "application/json")
                    .header("User-Agent", "QiLiangMarketIntelligence/1.0")
                    .GET().build();
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            byte[] bytes;
            try (InputStream body = response.body()) {
                if (response.statusCode() != 200)
                    throw new IllegalStateException("ROSARIO_HTTP_STATUS");
                bytes = body.readNBytes(65_537);
            }
            if (bytes.length > 65_536) throw new IllegalArgumentException("ROSARIO_RESPONSE_TOO_LARGE");
            snapshot = parse(mapper, new String(bytes, java.nio.charset.StandardCharsets.UTF_8),
                    clock.instant(), attemptedAt);
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            Snapshot previous = snapshot;
            snapshot = new Snapshot("SOURCE_UNAVAILABLE", previous.sourceDate(), previous.fetchedAt(),
                    attemptedAt, previous.sourceName(), previous.sourceUrl(), previous.attribution(),
                    previous.market(), previous.unit(), previous.observations(),
                    "ROSARIO_SOURCE_UNAVAILABLE");
        }
    }

    @GetMapping("/api/v1/market-intelligence/rosario-spot/overview")
    public ApiResponse<Snapshot> overview() {
        return new ApiResponse<>(snapshot);
    }
}
