package com.cofco.qiqihar.graintrade.overview.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class MapImageryTileGatewayTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void resolvesMonthlyProviderTemplateWithoutExposingTheCredentialAndCachesTiles() throws Exception {
        var requests = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/tiles/global_monthly_2026_08_mosaic/7/99/42.png", exchange -> {
            requests.incrementAndGet();
            assertThat(exchange.getRequestURI().getRawQuery()).isEqualTo("api_key=paid+secret");
            var bytes = "tile".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        var gateway = new MapImageryTileGateway(
                "http://127.0.0.1:" + server.getAddress().getPort()
                        + "/tiles/global_monthly_{year}_{month}_mosaic/{z}/{x}/{y}.png"
                        + "?api_key={apiKey}",
                "paid secret",
                "Planet monthly mosaic",
                "Planet",
                1,
                8_388_608,
                HttpClient.newHttpClient(),
                Clock.fixed(Instant.parse("2026-09-21T00:00:00Z"), ZoneOffset.UTC));

        var first = gateway.tile(7, 99, 42);
        var second = gateway.tile(7, 99, 42);

        assertThat(first.bytes()).containsExactly("tile".getBytes(StandardCharsets.UTF_8));
        assertThat(first.contentType()).isEqualTo("image/png");
        assertThat(first.period()).isEqualTo("2026-08");
        assertThat(first.etag()).startsWith("\"").endsWith("\"");
        assertThat(second.bytes()).isEqualTo(first.bytes());
        assertThat(requests).hasValue(1);
        assertThat(gateway.metadata().toString()).doesNotContain("paid secret");
        assertThat(gateway.metadata().commercialConfigured()).isTrue();
        assertThat(gateway.metadata().updateCadence()).isEqualTo("MONTHLY");
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
    void keepsThePreviousPublishedMosaicDuringTheProvidersPublicationWindow() {
        var gateway = new MapImageryTileGateway(
                "https://example.invalid/global_monthly_{year}_{month}/{z}/{x}/{y}.png",
                "paid",
                "Monthly imagery",
                "Example",
                1,
                8_388_608,
                HttpClient.newHttpClient(),
                Clock.fixed(Instant.parse("2026-10-05T00:00:00Z"), ZoneOffset.UTC));

        assertThat(gateway.metadata().imageryPeriod()).isEqualTo("2026-08");
        assertThat(gateway.metadata().automaticMonthlyPeriod()).isTrue();
    }
}
