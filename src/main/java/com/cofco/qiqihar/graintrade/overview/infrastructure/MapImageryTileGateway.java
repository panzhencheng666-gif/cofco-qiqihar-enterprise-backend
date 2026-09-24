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
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Same-origin gateway for governed satellite tiles. The provider URL and
 * credential stay on the server, while browser and reverse-proxy caches reuse
 * the returned immutable tile for the current weekly imagery period.
 */
@Component
public class MapImageryTileGateway {
    private static final Duration FRESH_FOR = Duration.ofDays(7);
    private static final Duration STALE_FOR = Duration.ofDays(56);
    private static final int MAXIMUM_ZOOM = 18;
    private static final int MAXIMUM_TILE_BYTES = 5 * 1024 * 1024;
    private static final String DEFAULT_TILE_URL_TEMPLATE =
            "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/"
                    + "MapServer/tile/{z}/{y}/{x}";

    private final String tileUrlTemplate;
    private final String apiKey;
    private final String provider;
    private final String attribution;
    private final int periodLagWeeks;
    private final long maximumCacheBytes;
    private final HttpClient http;
    private final Clock clock;
    private final LocalImageryReleaseStore localReleases;
    private final Map<TileKey, CacheEntry> cache = new LinkedHashMap<>(64, 0.75f, true);
    private long cachedBytes;

    @Autowired
    public MapImageryTileGateway(
            @Value("${qiqihar.map-imagery.tile-url-template:}") String tileUrlTemplate,
            @Value("${qiqihar.map-imagery.api-key:}") String apiKey,
            @Value("${qiqihar.map-imagery.provider:Enterprise imagery}") String provider,
            @Value("${qiqihar.map-imagery.attribution:Enterprise imagery service}") String attribution,
            @Value("${qiqihar.map-imagery.period-lag-weeks:0}") int periodLagWeeks,
            @Value("${qiqihar.map-imagery.maximum-cache-bytes:67108864}") long maximumCacheBytes,
            LocalImageryReleaseStore localReleases) {
        this(
                tileUrlTemplate,
                apiKey,
                provider,
                attribution,
                periodLagWeeks,
                maximumCacheBytes,
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(3))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                Clock.systemUTC(),
                localReleases);
    }

    MapImageryTileGateway(
            String tileUrlTemplate,
            String apiKey,
            String provider,
            String attribution,
            int periodLagWeeks,
            long maximumCacheBytes,
            HttpClient http,
            Clock clock) {
        this(
                tileUrlTemplate,
                apiKey,
                provider,
                attribution,
                periodLagWeeks,
                maximumCacheBytes,
                http,
                clock,
                null);
    }

    MapImageryTileGateway(
            String tileUrlTemplate,
            String apiKey,
            String provider,
            String attribution,
            int periodLagWeeks,
            long maximumCacheBytes,
            HttpClient http,
            Clock clock,
            LocalImageryReleaseStore localReleases) {
        tileUrlTemplate = tileUrlTemplate.isBlank()
                ? DEFAULT_TILE_URL_TEMPLATE
                : tileUrlTemplate;
        if (!tileUrlTemplate.contains("{z}")
                || !tileUrlTemplate.contains("{x}")
                || !tileUrlTemplate.contains("{y}")) {
            throw new IllegalArgumentException("Imagery URL template must contain {z}, {x}, and {y}");
        }
        if (periodLagWeeks < 0 || periodLagWeeks > 52) {
            throw new IllegalArgumentException("Imagery period lag must be between 0 and 52 weeks");
        }
        if (maximumCacheBytes < MAXIMUM_TILE_BYTES) {
            throw new IllegalArgumentException("Imagery cache must allow at least one maximum tile");
        }
        this.tileUrlTemplate = tileUrlTemplate;
        this.apiKey = apiKey;
        this.provider = provider;
        this.attribution = attribution;
        this.periodLagWeeks = periodLagWeeks;
        this.maximumCacheBytes = maximumCacheBytes;
        this.http = http;
        this.clock = clock;
        this.localReleases = localReleases;
    }

    public Tile tile(int zoom, int x, int y) throws IOException, InterruptedException {
        validateCoordinate(zoom, x, y);
        if (localReleases != null && localReleases.currentMetadata().isPresent()) {
            try {
                var tile = localReleases.currentTile(zoom, x, y);
                return new Tile(
                        tile.bytes(), tile.contentType(), tile.etag(), tile.version(), false);
            } catch (IOException outsideLocalCoverage) {
                // The public overview includes context outside Qiqihar. Keep the
                // historical fallback there instead of turning the whole raster
                // source into a partially missing layer.
            }
        }
        ImageryPeriod period = imageryPeriod();
        var key = new TileKey(period.id(), zoom, x, y);
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
            var previous = cachedPrevious(key);
            if (previous != null) return previous.tile(true);
            throw failure;
        }
    }

    public Tile tile(String version, int zoom, int x, int y) throws IOException {
        validateCoordinate(zoom, x, y);
        if (localReleases == null) throw new IOException("Local imagery releases are disabled");
        var tile = localReleases.tile(version, zoom, x, y);
        return new Tile(tile.bytes(), tile.contentType(), tile.etag(), tile.version(), false);
    }

    public Metadata metadata() {
        if (localReleases != null) {
            var local = localReleases.currentMetadata();
            if (local.isPresent()) {
                var metadata = local.orElseThrow();
                var status = metadata.status();
                try {
                    var age = Duration.between(Instant.parse(metadata.syncedAt()), clock.instant());
                    var maximumAge = "MONTHLY".equals(metadata.updateCadence())
                            ? Duration.ofDays(35) : Duration.ofDays(8);
                    if (age.compareTo(maximumAge) > 0 || age.compareTo(Duration.ofHours(-1)) < 0) {
                        status = "STALE";
                    }
                } catch (RuntimeException invalidTimestamp) {
                    status = "STALE";
                }
                return new Metadata(
                        metadata.provider(),
                        metadata.attribution(),
                        metadata.updateCadence(),
                        metadata.version(),
                        metadata.acquisitionFrom(),
                        metadata.acquisitionTo(),
                        false,
                        "WEEKLY".equals(metadata.updateCadence()),
                        metadata.syncedAt(),
                        metadata.spatialResolutionMeters(),
                        metadata.cloudCoveragePercent(),
                        status,
                        metadata.sourceProductIds(),
                        metadata.truthStatement());
            }
        }
        var period = imageryPeriod();
        boolean automaticWeeklyPeriod = automaticWeeklyPeriod();
        boolean weeklyConfigured = automaticWeeklyPeriod
                && (!tileUrlTemplate.contains("{apiKey}") || !apiKey.isBlank());
        return new Metadata(
                provider,
                attribution,
                weeklyConfigured ? "WEEKLY" : "UNVERSIONED_FALLBACK",
                period.id(),
                weeklyConfigured ? period.start().toString() : null,
                weeklyConfigured ? period.end().toString() : null,
                weeklyConfigured && !apiKey.isBlank(),
                automaticWeeklyPeriod,
                null,
                null,
                null,
                weeklyConfigured ? "CURRENT" : "FALLBACK",
                List.of(),
                weeklyConfigured
                        ? "Latest available governed weekly observation; not live video."
                        : "Unversioned historical fallback; no weekly freshness claim.");
    }

    private CacheEntry fetch(ImageryPeriod period, int zoom, int x, int y)
            throws IOException, InterruptedException {
        if (tileUrlTemplate.contains("{apiKey}") && apiKey.isBlank()) {
            throw new IOException("Commercial imagery credential is not configured");
        }
        String url = tileUrlTemplate
                .replace("{period}", period.id())
                .replace("{year}", Integer.toString(period.weekBasedYear()))
                .replace("{week}", "%02d".formatted(period.week()))
                .replace("{periodStart}", period.start().toString())
                .replace("{periodEnd}", period.end().toString())
                .replace(
                        "{periodStartEncoded}",
                        URLEncoder.encode(period.start().toString(), StandardCharsets.UTF_8))
                .replace(
                        "{periodEndEncoded}",
                        URLEncoder.encode(period.end().toString(), StandardCharsets.UTF_8))
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
        return new CacheEntry(
                bytes.clone(), contentType, etag(bytes), period.id(), clock.instant());
    }

    private synchronized CacheEntry cached(TileKey key) {
        return cache.get(key);
    }

    private synchronized CacheEntry cachedPrevious(TileKey current) {
        CacheEntry candidate = null;
        String candidatePeriod = "";
        Instant now = clock.instant();
        for (var entry : cache.entrySet()) {
            var key = entry.getKey();
            var value = entry.getValue();
            if (key.zoom() != current.zoom()
                    || key.x() != current.x()
                    || key.y() != current.y()
                    || key.period().equals(current.period())
                    || key.period().compareTo(current.period()) > 0
                    || value.age(now).compareTo(STALE_FOR) > 0) continue;
            if (key.period().compareTo(candidatePeriod) > 0) {
                candidate = value;
                candidatePeriod = key.period();
            }
        }
        return candidate;
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

    private ImageryPeriod imageryPeriod() {
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        LocalDate currentWeekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate end = currentWeekStart.minusDays(1).minusWeeks(periodLagWeeks);
        LocalDate start = end.minusDays(6);
        int weekBasedYear = start.get(IsoFields.WEEK_BASED_YEAR);
        int week = start.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        return new ImageryPeriod(
                automaticWeeklyPeriod()
                        ? "%d-W%02d".formatted(weekBasedYear, week)
                        : "UNVERSIONED",
                start,
                end,
                weekBasedYear,
                week);
    }

    private boolean automaticWeeklyPeriod() {
        return tileUrlTemplate.contains("{period}")
                || tileUrlTemplate.contains("{week}")
                || tileUrlTemplate.contains("{periodStart}")
                || tileUrlTemplate.contains("{periodEnd}")
                || tileUrlTemplate.contains("{periodStartEncoded}")
                || tileUrlTemplate.contains("{periodEndEncoded}");
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
            String acquisitionFrom,
            String acquisitionTo,
            boolean commercialConfigured,
            boolean automaticWeeklyPeriod,
            String syncedAt,
            Integer spatialResolutionMeters,
            Double cloudCoveragePercent,
            String status,
            List<String> sourceProductIds,
            String truthStatement) {
        public Metadata {
            sourceProductIds = List.copyOf(sourceProductIds);
        }
    }

    private record ImageryPeriod(
            String id, LocalDate start, LocalDate end, int weekBasedYear, int week) {}

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
