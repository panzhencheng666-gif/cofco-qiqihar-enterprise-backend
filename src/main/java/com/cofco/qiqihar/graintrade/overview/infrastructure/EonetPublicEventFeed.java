package com.cofco.qiqihar.graintrade.overview.infrastructure;

import com.cofco.qiqihar.graintrade.overview.application.OperationalSituationRepository;
import com.cofco.qiqihar.graintrade.overview.application.PublicEventFeed;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class EonetPublicEventFeed implements PublicEventFeed {
    static final URI ENDPOINT = URI.create(
            "https://eonet.gsfc.nasa.gov/api/v3/events?status=open&days=30&limit=500");
    private final HttpClient http;
    private final ObjectMapper json;

    @org.springframework.beans.factory.annotation.Autowired
    public EonetPublicEventFeed(ObjectMapper json) {
        this(json, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
                .followRedirects(HttpClient.Redirect.NEVER).build());
    }

    EonetPublicEventFeed(ObjectMapper json, HttpClient http) {
        this.json = json;
        this.http = http;
    }

    @Override
    public List<OperationalSituationRepository.FeedEvent> fetch() throws Exception {
        var request = HttpRequest.newBuilder(ENDPOINT).timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json")
                .header("User-Agent", "COFCO-Qiqihar-PublicSituation/1.0")
                .GET().build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300)
            throw new IllegalStateException("NASA EONET HTTP " + response.statusCode());
        if (response.body().length > 5_000_000) throw new IllegalStateException("NASA EONET 响应超过5MB");
        return parse(json.readTree(response.body()));
    }

    static List<OperationalSituationRepository.FeedEvent> parse(JsonNode root) {
        var events = new ArrayList<OperationalSituationRepository.FeedEvent>();
        for (JsonNode event : root.path("events")) {
            JsonNode geometry = latestPoint(event.path("geometry"));
            if (geometry == null) continue;
            JsonNode coordinates = geometry.path("coordinates");
            if (!coordinates.isArray() || coordinates.size() < 2) continue;
            JsonNode category = event.path("categories").path(0);
            String id = limited(event.path("id").asString(""), 120);
            String title = limited(event.path("title").asString(""), 300);
            String categoryCode = limited(category.path("id").asString("unknown"), 80);
            String categoryLabel = limited(category.path("title").asString(categoryCode), 160);
            String eventUrl = https(event.path("link").asString(""));
            if (id.isBlank() || title.isBlank() || eventUrl == null) continue;
            Instant observed;
            try {
                observed = Instant.parse(geometry.path("date").asString());
            } catch (RuntimeException invalid) {
                continue;
            }
            BigDecimal longitude = coordinates.path(0).decimalValue();
            BigDecimal latitude = coordinates.path(1).decimalValue();
            if (longitude.compareTo(BigDecimal.valueOf(-180)) < 0
                    || longitude.compareTo(BigDecimal.valueOf(180)) > 0
                    || latitude.compareTo(BigDecimal.valueOf(-90)) < 0
                    || latitude.compareTo(BigDecimal.valueOf(90)) > 0) continue;
            String evidence = null;
            for (JsonNode source : event.path("sources")) {
                evidence = https(source.path("url").asString(""));
                if (evidence != null) break;
            }
            events.add(new OperationalSituationRepository.FeedEvent(
                    id, title, nullable(limited(event.path("description").asString(""), 2_000)),
                    categoryCode, categoryLabel, longitude, latitude, observed,
                    geometry.path("magnitudeValue").isNumber()
                            ? geometry.path("magnitudeValue").decimalValue() : null,
                    nullable(limited(geometry.path("magnitudeUnit").asString(""), 80)),
                    eventUrl, evidence));
        }
        return events.stream().sorted(Comparator
                .comparing(OperationalSituationRepository.FeedEvent::observedAt).reversed()
                .thenComparing(OperationalSituationRepository.FeedEvent::eventId)).toList();
    }

    private static JsonNode latestPoint(JsonNode geometries) {
        JsonNode latest = null;
        Instant latestAt = Instant.MIN;
        for (JsonNode geometry : geometries) {
            if (!"Point".equals(geometry.path("type").asString())) continue;
            try {
                Instant value = Instant.parse(geometry.path("date").asString());
                if (value.isAfter(latestAt)) {
                    latest = geometry;
                    latestAt = value;
                }
            } catch (RuntimeException ignored) {
                // Skip source geometries without an ISO observation time.
            }
        }
        return latest;
    }

    private static String https(String value) {
        try {
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    && uri.getUserInfo() == null ? uri.toString() : null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static String limited(String value, int maximum) {
        String clean = value == null ? "" : value.strip();
        return clean.substring(0, Math.min(maximum, clean.length()));
    }

    private static String nullable(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
