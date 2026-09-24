package com.cofco.qiqihar.graintrade.overview.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class MapImageryTileGatewayTest {
    private HttpServer server;

    @TempDir
    Path temporary;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void resolvesWeeklyProviderTemplateWithoutExposingTheCredentialAndCachesTiles() throws Exception {
        var requests = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/tiles/2026-W38/7/99/42.png", exchange -> {
            requests.incrementAndGet();
            assertThat(exchange.getRequestURI().getRawQuery())
                    .isEqualTo("time=2026-09-14%2F2026-09-20&api_key=paid+secret");
            var bytes = "tile".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        var gateway = new MapImageryTileGateway(
                "http://127.0.0.1:" + server.getAddress().getPort()
                        + "/tiles/{period}/{z}/{x}/{y}.png"
                        + "?time={periodStartEncoded}%2F{periodEndEncoded}&api_key={apiKey}",
                "paid secret",
                "Governed weekly mosaic",
                "Licensed provider",
                0,
                8_388_608,
                HttpClient.newHttpClient(),
                Clock.fixed(Instant.parse("2026-09-21T00:00:00Z"), ZoneOffset.UTC));

        var first = gateway.tile(7, 99, 42);
        var second = gateway.tile(7, 99, 42);

        assertThat(first.bytes()).containsExactly("tile".getBytes(StandardCharsets.UTF_8));
        assertThat(first.contentType()).isEqualTo("image/png");
        assertThat(first.period()).isEqualTo("2026-W38");
        assertThat(first.etag()).startsWith("\"").endsWith("\"");
        assertThat(second.bytes()).isEqualTo(first.bytes());
        assertThat(requests).hasValue(1);
        assertThat(gateway.metadata().toString()).doesNotContain("paid secret");
        assertThat(gateway.metadata().commercialConfigured()).isTrue();
        assertThat(gateway.metadata().updateCadence()).isEqualTo("WEEKLY");
        assertThat(gateway.metadata().imageryPeriod()).isEqualTo("2026-W38");
        assertThat(gateway.metadata().acquisitionFrom()).isEqualTo("2026-09-14");
        assertThat(gateway.metadata().acquisitionTo()).isEqualTo("2026-09-20");
        assertThat(gateway.metadata().automaticWeeklyPeriod()).isTrue();
    }

    @Test
    void rejectsInvalidCoordinatesBeforeAnyUpstreamRequest() {
        var gateway = new MapImageryTileGateway(
                "https://example.invalid/{z}/{x}/{y}.png",
                "",
                "Fallback imagery",
                "Example",
                1,
                8_388_608,
                HttpClient.newHttpClient(),
                Clock.systemUTC());

        assertThatThrownBy(() -> gateway.tile(7, 128, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tile coordinate");
        assertThatThrownBy(() -> gateway.tile(19, 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("zoom");
    }

    @Test
    void doesNotDescribeAnUnversionedFallbackAsWeeklyImagery() {
        var gateway = new MapImageryTileGateway(
                "https://example.invalid/{z}/{x}/{y}.png",
                "",
                "Historical fallback",
                "Example",
                0,
                8_388_608,
                HttpClient.newHttpClient(),
                Clock.fixed(Instant.parse("2026-09-22T00:00:00Z"), ZoneOffset.UTC));

        assertThat(gateway.metadata().updateCadence()).isEqualTo("UNVERSIONED_FALLBACK");
        assertThat(gateway.metadata().imageryPeriod()).isEqualTo("UNVERSIONED");
        assertThat(gateway.metadata().acquisitionFrom()).isNull();
        assertThat(gateway.metadata().acquisitionTo()).isNull();
        assertThat(gateway.metadata().automaticWeeklyPeriod()).isFalse();
    }

    @Test
    void usesTheMostRecentCompleteUtcWeek() {
        var gateway = new MapImageryTileGateway(
                "https://example.invalid/{periodStart}/{periodEnd}/{z}/{x}/{y}.png",
                "paid",
                "Weekly imagery",
                "Example",
                0,
                8_388_608,
                HttpClient.newHttpClient(),
                Clock.fixed(Instant.parse("2026-09-22T00:00:00Z"), ZoneOffset.UTC));

        assertThat(gateway.metadata().imageryPeriod()).isEqualTo("2026-W38");
        assertThat(gateway.metadata().acquisitionFrom()).isEqualTo("2026-09-14");
        assertThat(gateway.metadata().acquisitionTo()).isEqualTo("2026-09-20");
        assertThat(gateway.metadata().automaticWeeklyPeriod()).isTrue();
    }

    @Test
    void fallsBackToThePreviousSuccessfulWeekWhenTheNewWeekIsUnavailable() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/tiles/2026-W38/7/99/42.png", exchange -> {
            var bytes = "previous-week".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.createContext("/tiles/2026-W39/7/99/42.png", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        var clock = new MutableClock(Instant.parse("2026-09-22T00:00:00Z"));
        var gateway = new MapImageryTileGateway(
                "http://127.0.0.1:" + server.getAddress().getPort()
                        + "/tiles/{period}/{z}/{x}/{y}.png",
                "paid",
                "Weekly imagery",
                "Example",
                0,
                8_388_608,
                HttpClient.newHttpClient(),
                clock);

        assertThat(gateway.tile(7, 99, 42).period()).isEqualTo("2026-W38");
        clock.instant = Instant.parse("2026-09-29T00:00:00Z");

        var fallback = gateway.tile(7, 99, 42);

        assertThat(fallback.period()).isEqualTo("2026-W38");
        assertThat(fallback.stale()).isTrue();
        assertThat(fallback.bytes())
                .containsExactly("previous-week".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void localReleasePrecedesTheRemoteProviderAndExposesTruthfulMetadata() throws Exception {
        var release = temporary.resolve("releases/2026-09");
        var tilePath = release.resolve("tiles/14/13871/5612.webp");
        Files.createDirectories(tilePath.getParent());
        Files.write(tilePath, "local-week".getBytes(StandardCharsets.UTF_8));
        Files.writeString(
                release.resolve("metadata.json"),
                """
                {"version":"2026-09","provider":"Copernicus Sentinel-2 L2A",
                 "attribution":"European Union, Copernicus Sentinel-2 imagery",
                 "updateCadence":"MONTHLY","acquisitionFrom":"2026-09-18T02:00:00Z",
                 "acquisitionTo":"2026-09-20T02:00:00Z","syncedAt":"2026-09-21T03:10:00Z",
                 "spatialResolutionMeters":10,"cloudCoveragePercent":8.5,"status":"CURRENT",
                 "sourceProductIds":["S2-test"],
                 "truthStatement":"Latest available observation; not live video."}
                """);
        Files.createSymbolicLink(temporary.resolve("current"), release);
        var gateway = new MapImageryTileGateway(
                "https://example.invalid/{z}/{x}/{y}.png",
                "",
                "Historical fallback",
                "Example",
                0,
                8_388_608,
                HttpClient.newHttpClient(),
                Clock.fixed(Instant.parse("2026-09-23T00:00:00Z"), ZoneOffset.UTC),
                new LocalImageryReleaseStore(temporary.toString(), new ObjectMapper()));

        var current = gateway.tile(14, 13871, 5612);
        var immutable = gateway.tile("2026-09", 14, 13871, 5612);

        assertThat(current.bytes()).containsExactly("local-week".getBytes(StandardCharsets.UTF_8));
        assertThat(immutable.bytes()).isEqualTo(current.bytes());
        assertThat(gateway.metadata().imageryPeriod()).isEqualTo("2026-09");
        assertThat(gateway.metadata().updateCadence()).isEqualTo("MONTHLY");
        assertThat(gateway.metadata().automaticWeeklyPeriod()).isFalse();
        assertThat(gateway.metadata().spatialResolutionMeters()).isEqualTo(10);
        assertThat(gateway.metadata().cloudCoveragePercent()).isEqualTo(8.5);
        assertThat(gateway.metadata().status()).isEqualTo("CURRENT");

        var expiredGateway = new MapImageryTileGateway(
                "https://example.invalid/{z}/{x}/{y}.png", "", "Historical fallback", "Example",
                0, 8_388_608, HttpClient.newHttpClient(),
                Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC),
                new LocalImageryReleaseStore(temporary.toString(), new ObjectMapper()));
        assertThat(expiredGateway.metadata().status()).isEqualTo("STALE");
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
