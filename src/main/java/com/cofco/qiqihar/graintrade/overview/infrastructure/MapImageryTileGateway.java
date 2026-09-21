package com.cofco.qiqihar.graintrade.overview.infrastructure;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Same-origin gateway for governed satellite tiles. The provider URL and
 * credential stay on the server, while browser and reverse-proxy caches reuse
 * the returned immutable tile for the current monthly imagery period.
 */
@Component
public class MapImageryTileGateway {
    private static final Duration FRESH_FOR = Duration.ofDays(7);
    private static final Duration STALE_FOR = Duration.ofDays(35);
    private static final int MAXIMUM_ZOOM = 18;
    private static final int MAXIMUM_TILE_BYTES = 5 * 1024 * 1024;
    private static final int MONTHLY_PUBLICATION_BUFFER_DAYS = 7;
    private static final String DEFAULT_TILE_URL_TEMPLATE =
            "https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/"
                    + "MapServer/tile/{z}/{y}/{x}";

    private final String tileUrlTemplate;
    private final String apiKey;
    private final String provider;
    private final String attribution;
    private final int periodLagMonths;
    private final long maximumCacheBytes;
    private final HttpClient http;
    private final Clock clock;
    private final Map<TileKey, CacheEntry> cache = new LinkedHashMap<>(64, 0.75f, true);
    private long cachedBytes;

    @Autowired
    public MapImageryTileGateway(
            @Value("${qiqihar.map-imagery.tile-url-template:}") String tileUrlTemplate,
            @Value("${qiqihar.map-imagery.api-key:}") String apiKey,
            @Value("${qiqihar.map-imagery.provider:Enterprise imagery}") String provider,
            @Value("${qiqihar.map-imagery.attribution:Enterprise imagery service}") String attribution,
            @Value("${qiqihar.map-imagery.period-lag-months:1}") int periodLagMonths,
            @Value("${qiqihar.map-imagery.maximum-cache-bytes:67108864}") long maximumCacheBytes) {
        this(
                tileUrlTemplate,
                apiKey,
                provider,
                attribution,
                periodLagMonths,
                maximumCacheBytes,
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(3))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                Clock.systemUTC());
    }

    MapImageryTileGateway(
            String tileUrlTemplate,
            String apiKey,
            String provider,
            String attribution,
            int periodLagMonths,
            long maximumCacheBytes,
            HttpClient http,
            Clock clock) {
        tileUrlTemplate = tileUrlTemplate.isBlank()
                ? DEFAULT_TILE_URL_TEMPLATE
                : tileUrlTemplate;
        if (!tileUrlTemplate.contains("{z}")
                || !tileUrlTemplate.contains("{x}")
                || !tileUrlTemplate.contains("{y}")) {
            throw new IllegalArgumentException("Imagery URL template must contain {z}, {x}, and {y}");
        }
        if (periodLagMonths < 0 || periodLagMonths > 12) {
            throw new IllegalArgumentException("Imagery period lag must be between 0 and 12 months");
        }
        if (maximumCacheBytes < MAXIMUM_TILE_BYTES) {
            throw new IllegalArgumentException("Imagery cache must allow at least one maximum tile");
        }
        this.tileUrlTemplate = tileUrlTemplate;
        this.apiKey = apiKey;
        this.provider = provider;
        this.attribution = attribution;
        this.periodLagMonths = periodLagMonths;
        this.maximumCacheBytes = maximumCacheBytes;
        this.http = http;
        this.clock = clock;
    }

    public Tile tile(int zoom, int x, int y) throws IOException, InterruptedException {
        validateCoordinate(zoom, x, y);
        String period = imageryPeriod();
        var key = new TileKey(period, zoom, x, y);
        var cached = cached(key);
        if (cached != null && cached.age(clock.instant()).compareTo(FRESH_FOR) <= 0) {
            return cached.tile(false);
        }
        try {
            var fetched = fetch(period, zoom, x, y);
            retain(key, fetched);
            return fetched.tile(false);
        } catch (IOException | InterruptedException failure) {
            if (cached != null && cached.age(clock.instant()).compareTo(STALE_FOR) <= 0) {
                return cached.tile(true);
            }
            throw failure;
        }
    }

    public Metadata metadata() {
        return new Metadata(
                provider,
                attribution,
                "MONTHLY",
                imageryPeriod(),
                !apiKey.isBlank(),
                tileUrlTemplate.contains("{period}")
                        || tileUrlTemplate.contains("{year}")
                        || tileUrlTemplate.contains("{month}"));
    }

    private CacheEntry fetch(String period, int zoom, int x, int y)
            throws IOException, InterruptedException {
        if (tileUrlTemplate.contains("{apiKey}") && apiKey.isBlank()) {
            throw new IOException("Commercial imagery credential is not configured");
        }
        String url = tileUrlTemplate
                .replace("{period}", period)
                .replace("{year}", period.substring(0, 4))
                .replace("{month}", period.substring(5, 7))
                .replace("{z}", Integer.toString(zoom))
                .replace("{x}", Integer.toString(x))
                .replace("{y}", Integer.toString(y))
                .replace("{apiKey}", URLEncoder.encode(apiKey, StandardCharsets.UTF_8));
        var request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(6))
                .header("Accept", "image/avif,image/webp,image/png,image/jpeg")
                .GET()
                .build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Imagery provider returned status " + response.statusCode());
        }
        byte[] bytes = response.body();
        if (bytes.length == 0 || bytes.length > MAXIMUM_TILE_BYTES) {
            throw new IOException("Imagery provider returned an invalid tile size");
        }
        String contentType = response.headers()
                .firstValue("content-type")
                .map(value -> value.split(";", 2)[0].strip().toLowerCase())
                .orElse("");
        if (!contentType.equals("image/png")
                && !contentType.equals("image/jpeg")
                && !contentType.equals("image/webp")
                && !contentType.equals("image/avif")) {
            throw new IOException("Imagery provider returned an unsupported media type");
        }
        return new CacheEntry(bytes.clone(), contentType, etag(bytes), period, clock.instant());
    }

    private synchronized CacheEntry cached(TileKey key) {
        return cache.get(key);
    }

    private synchronized void retain(TileKey key, CacheEntry entry) {
        CacheEntry previous = cache.put(key, entry);
        if (previous != null) cachedBytes -= previous.bytes().length;
        cachedBytes += entry.bytes().length;
        var iterator = cache.entrySet().iterator();
        while (cachedBytes > maximumCacheBytes && iterator.hasNext()) {
            var eldest = iterator.next();
            cachedBytes -= eldest.getValue().bytes().length;
            iterator.remove();
        }
    }

    private String imageryPeriod() {
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        YearMonth period = YearMonth.from(today).minusMonths(periodLagMonths);
        if (today.getDayOfMonth() <= MONTHLY_PUBLICATION_BUFFER_DAYS) {
            period = period.minusMonths(1);
        }
        return period.toString();
    }

    private static void validateCoordinate(int zoom, int x, int y) {
        if (zoom < 0 || zoom > MAXIMUM_ZOOM) {
            throw new IllegalArgumentException("Invalid imagery zoom");
        }
        int limit = 1 << zoom;
        if (x < 0 || y < 0 || x >= limit || y >= limit) {
            throw new IllegalArgumentException("Invalid imagery tile coordinate");
        }
    }

    private static String etag(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return "\"" + HexFormat.of().formatHex(digest) + "\"";
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    public record Tile(
            byte[] bytes, String contentType, String etag, String period, boolean stale) {
        public Tile {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    public record Metadata(
            String provider,
            String attribution,
            String updateCadence,
            String imageryPeriod,
            boolean commercialConfigured,
            boolean automaticMonthlyPeriod) {}

    private record TileKey(String period, int zoom, int x, int y) {}

    private record CacheEntry(
            byte[] bytes, String contentType, String etag, String period, Instant fetchedAt) {
        Duration age(Instant now) {
            return Duration.between(fetchedAt, now);
        }

        Tile tile(boolean stale) {
            return new Tile(bytes, contentType, etag, period, stale);
        }
    }
}
