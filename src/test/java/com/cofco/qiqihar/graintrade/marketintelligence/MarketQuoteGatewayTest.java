package com.cofco.qiqihar.graintrade.marketintelligence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class MarketQuoteGatewayTest {
    @Test
    void requiresExplicitDistributionAuthorizationBeforeFetching() {
        var gateway = new MarketQuoteGateway(new ObjectMapper(), "http://127.0.0.1:1/quotes", "", false);
        gateway.refresh();
        var board = gateway.overview().data();
        assertEquals("PENDING_AUTHORIZATION", board.gatewayState());
        assertNull(board.lastAttemptAt());
        assertEquals(0, board.quotes().size());
        assertFalse(board.instruments().isEmpty());
    }

    @Test
    void acceptsOnlyKnownAttributedQuotesAndMarksOldTicksStale() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/quotes", exchange -> {
            var payload = ("""
                    {"quotes":[
                      {"id":"cbot-corn","last":460.25,"previousClose":455.5,"sourceAt":"%s","provider":"licensed-test"},
                      {"id":"unknown","last":123,"sourceAt":"%s","provider":"licensed-test"},
                      {"id":"dxy","last":99,"sourceAt":"invalid","provider":"licensed-test"}
                    ]}
                    """).formatted(Instant.now().minusSeconds(180), Instant.now());
            var bytes = payload.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        try {
            var gateway = new MarketQuoteGateway(new ObjectMapper(),
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/quotes", "", true);
            gateway.refresh();
            var board = gateway.overview().data();
            assertEquals("CONNECTED", board.gatewayState());
            assertEquals(1, board.quotes().size());
            assertEquals("cbot-corn", board.quotes().getFirst().id());
            assertEquals("STALE", board.quotes().getFirst().state());
            assertEquals("licensed-test", board.quotes().getFirst().provider());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void partialAndDelayedBatchesKeepTheNewestAttributedTick() throws Exception {
        var newer = Instant.now().minusSeconds(5);
        var older = newer.minusSeconds(30);
        var payload = new AtomicReference<>(("""
                {"quotes":[
                  {"id":"cbot-corn","last":100,"sourceAt":"%s","provider":"licensed-test"},
                  {"id":"cbot-corn","last":90,"sourceAt":"%s","provider":"licensed-test"},
                  {"id":"dxy","last":99,"sourceAt":"%s","provider":"licensed-test"}
                ]}
                """).formatted(newer, older, newer));
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/quotes", exchange -> {
            var bytes = payload.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        try {
            var gateway = new MarketQuoteGateway(new ObjectMapper(),
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/quotes", "", true);
            gateway.refresh();
            payload.set(("""
                    {"quotes":[{"id":"cbot-corn","last":80,"sourceAt":"%s","provider":"licensed-test"}]}
                    """).formatted(older));
            gateway.refresh();
            var quotes = gateway.overview().data().quotes();
            assertEquals(2, quotes.size());
            assertEquals(100, quotes.stream().filter(quote -> quote.id().equals("cbot-corn"))
                    .findFirst().orElseThrow().last().intValueExact());
            assertEquals(99, quotes.stream().filter(quote -> quote.id().equals("dxy"))
                    .findFirst().orElseThrow().last().intValueExact());
        } finally {
            server.stop(0);
        }
    }
}
