package com.cofco.qiqihar.graintrade.marketintelligence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class MarketQuoteGatewayTest {
    private static final Set<String> TEST_SCOPE = Set.of("dce-corn", "cbot-corn", "dxy",
            "dce-soybean", "czce-wheat", "czce-common-wheat", "czce-rice");
    private static String envelope(String state, Instant publishedAt, String content) {
        return "{\"schemaVersion\":1,\"state\":\"" + state + "\",\"publishedAt\":\""
                + publishedAt + "\"," + content.substring(1);
    }

    private static String currentQuote() {
        return "{\"quotes\":[{\"id\":\"dce-corn\",\"last\":2000,\"sourceAt\":\""
                + Instant.now() + "\",\"provider\":\"synthetic-test\","
                + "\"exchangeId\":\"DCE\",\"productId\":\"c\","
                + "\"contractId\":\"DCE.c2601\",\"unit\":\"元/吨\"}]}";
    }

    private static void withFeed(String initial,
            BiConsumer<MarketQuoteGateway, AtomicReference<String>> assertions) throws Exception {
        withFeed(initial, Clock.systemUTC(), assertions);
    }

    private static void withFeed(String initial, Clock clock,
            BiConsumer<MarketQuoteGateway, AtomicReference<String>> assertions) throws Exception {
        withFeed(initial, TEST_SCOPE, clock, assertions);
    }

    private static void withFeed(String initial, Set<String> displayScope,
            BiConsumer<MarketQuoteGateway, AtomicReference<String>> assertions) throws Exception {
        withFeed(initial, displayScope, Clock.systemUTC(), assertions);
    }

    private static void withFeed(String initial, Set<String> displayScope, Clock clock,
            BiConsumer<MarketQuoteGateway, AtomicReference<String>> assertions) throws Exception {
        var payload = new AtomicReference<>(initial);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/quotes", exchange -> {
            var bytes = payload.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        try {
            assertions.accept(new MarketQuoteGateway(new ObjectMapper(),
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/quotes", "", true,
                    displayScope, clock), payload);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void recoveryStateMustNotBePromotedToConnectedEvenWithFreshPrices() throws Exception {
        withFeed(envelope("RECOVERY_REQUIRED", Instant.now(), currentQuote()), (gateway, payload) -> {
            gateway.refresh();
            assertEquals("SOURCE_ERROR", gateway.overview().data().gatewayState());
            assertTrue(gateway.overview().data().quotes().isEmpty());
        });
    }

    @Test
    void permissionLossClearsCachedPricesAndReconnectNeedsReconciliation() throws Exception {
        withFeed(envelope("RECONCILED", Instant.now(), currentQuote()), (gateway, payload) -> {
            gateway.refresh();
            assertEquals("CONNECTED", gateway.overview().data().gatewayState());
            var sourceAt = gateway.overview().data().quotes().getFirst().sourceAt();
            payload.set(envelope("RECONNECTING", Instant.now(), "{\"quotes\":[]}"));
            gateway.refresh();
            assertEquals("SOURCE_ERROR", gateway.overview().data().gatewayState());
            assertEquals(sourceAt, gateway.overview().data().quotes().getFirst().sourceAt());
            payload.set(envelope("ENTITLEMENT_ERROR", Instant.now(), currentQuote()));
            gateway.refresh();
            assertTrue(gateway.overview().data().quotes().isEmpty());
            assertEquals("PENDING_AUTHORIZATION", gateway.overview().data().gatewayState());
            payload.set(envelope("RECOVERY_REQUIRED", Instant.now(), currentQuote()));
            gateway.refresh();
            assertTrue(gateway.overview().data().quotes().isEmpty());
            payload.set(envelope("RECONCILED", Instant.now(), currentQuote()));
            gateway.refresh();
            assertEquals("CONNECTED", gateway.overview().data().gatewayState());
        });
    }

    @Test
    void missingUnknownOrExpiredHealthCannotCertifyFreshQuotes() throws Exception {
        withFeed(currentQuote(), (gateway, payload) -> {
            for (String body : java.util.List.of(currentQuote(),
                    envelope("TYPO", Instant.now(), currentQuote()),
                    envelope("RECONCILED", Instant.now().minusSeconds(40), currentQuote()),
                    envelope("RECONCILED", Instant.now().plusSeconds(60), currentQuote()))) {
                payload.set(body);
                gateway.refresh();
                assertEquals("SOURCE_ERROR", gateway.overview().data().gatewayState());
                assertTrue(gateway.overview().data().quotes().isEmpty());
            }
        });
    }

    @Test
    void stoppedWorkerExpiresEvenWhenTheLastQuoteIsStillYoung() throws Exception {
        Instant started = Instant.now();
        var time = new AtomicReference<>(started);
        Clock clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return time.get(); }
        };
        String row = currentQuote();
        withFeed(envelope("RECONCILED", started, row), clock, (gateway, payload) -> {
            gateway.refresh();
            var initial = gateway.overview().data();
            assertEquals("RECONCILED", initial.feedState());
            time.set(started.plusSeconds(31));
            var stopped = gateway.overview().data();
            assertEquals("SOURCE_ERROR", stopped.gatewayState());
            assertEquals("QUOTE_FEED_HEARTBEAT_STALE", stopped.lastError());
            assertEquals(31L, stopped.feedAgeSeconds());
            assertEquals(initial.lastSuccessAt(), stopped.lastSuccessAt());
            assertEquals(initial.quotes().getFirst().sourceAt(), stopped.quotes().getFirst().sourceAt());
            payload.set(envelope("RECONCILED", time.get(), row));
            gateway.refresh();
            assertEquals("CONNECTED", gateway.overview().data().gatewayState());
        });
    }

    @Test
    void replayedHealthyEnvelopeCannotUndoARecentPermissionFailure() throws Exception {
        Instant now = Instant.now();
        withFeed(envelope("ENTITLEMENT_ERROR", now, currentQuote()), (gateway, payload) -> {
            gateway.refresh();
            for (Instant timestamp : java.util.List.of(now.minusSeconds(1), now)) {
                payload.set(envelope("RECONCILED", timestamp, currentQuote()));
                gateway.refresh();
                assertFalse("CONNECTED".equals(gateway.overview().data().gatewayState()));
                assertTrue(gateway.overview().data().quotes().isEmpty());
                assertEquals("QUOTE_FEED_OUT_OF_ORDER_HEALTH", gateway.overview().data().lastError());
            }
        });
    }
    @Test
    void grainAndAgriculturalCatalogueIsVisibleWithoutInventingPrices() {
        var board = new MarketQuoteGateway(new ObjectMapper(), "", "", false).overview().data();
        var instruments = board.instruments();
        var ids = instruments.stream().map(MarketQuoteGateway.Instrument::id).collect(java.util.stream.Collectors.toSet());
        assertEquals(instruments.size(), ids.size());
        assertTrue(instruments.stream().filter(item -> "中国".equals(item.market())).count() * 2 >= instruments.size());
        assertTrue(ids.containsAll(Set.of("dce-soybean", "dce-soybean-2", "dce-corn", "dce-japonica-rice",
                "czce-wheat", "czce-early-indica-rice", "czce-peanut", "dce-egg")));
        assertTrue(instruments.stream().anyMatch(item -> "谷物".equals(item.group())));
        assertTrue(instruments.stream().anyMatch(item -> "农副产品".equals(item.group())));
        assertEquals("PENDING_AUTHORIZATION", board.gatewayState());
        assertTrue(board.quotes().isEmpty());
    }

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
    void globalAuthorizationWithoutAnInstrumentDisplayScopeCannotFetchOrDisplay() {
        var gateway = new MarketQuoteGateway(new ObjectMapper(), "http://127.0.0.1:1/quotes", "", true,
                Set.of());
        gateway.refresh();
        var board = gateway.overview().data();
        assertEquals("PENDING_CONFIGURATION", board.gatewayState());
        assertNull(board.lastAttemptAt());
        assertTrue(board.quotes().isEmpty());
    }

    @Test
    void onlyExplicitlyDisplayAllowedInstrumentsReachTheBoard() throws Exception {
        Instant now = Instant.now();
        String body = "{\"quotes\":["
                + grainRow("dce-corn", "DCE", "c", "DCE.c2601", now) + ","
                + "{\"id\":\"dxy\",\"last\":99,\"sourceAt\":\"" + now
                + "\",\"provider\":\"synthetic-test\"}]}";
        withFeed(envelope("RECONCILED", now, body), Set.of("dce-corn"), (gateway, payload) -> {
            gateway.refresh();
            var board = gateway.overview().data();
            assertEquals("CONNECTED", board.gatewayState());
            assertEquals(1, board.quotes().size());
            assertEquals("dce-corn", board.quotes().getFirst().id());
        });
    }

    @Test
    void invalidDisplayScopeCannotSilentlyEnableTheWrongInstrument() {
        var configured = new MarketQuoteGateway(new ObjectMapper(), "http://127.0.0.1:1/quotes", "",
                true, " dce-corn ");
        assertEquals("WAITING_FIRST_TICK", configured.overview().data().gatewayState());
        assertThrows(IllegalArgumentException.class, () -> new MarketQuoteGateway(new ObjectMapper(),
                "http://127.0.0.1:1/quotes", "", true, "dce-corn,unknown"));
        assertThrows(IllegalArgumentException.class, () -> new MarketQuoteGateway(new ObjectMapper(),
                "http://127.0.0.1:1/quotes", "", true, "dce-corn,dce-corn"));
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
            var bytes = envelope("RECONCILED", Instant.now(), payload).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        try {
            var gateway = new MarketQuoteGateway(new ObjectMapper(),
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/quotes", "", true,
                    TEST_SCOPE);
            gateway.refresh();
            var board = gateway.overview().data();
            assertEquals("STALE_DATA", board.gatewayState());
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
            var bytes = envelope("RECONCILED", Instant.now(), payload.get()).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        try {
            var gateway = new MarketQuoteGateway(new ObjectMapper(),
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/quotes", "", true,
                    TEST_SCOPE);
            gateway.refresh();
            payload.set(("""
                    {"quotes":[{"id":"cbot-corn","last":80,"sourceAt":"%s","provider":"licensed-test"}]}
                    """).formatted(older));
            gateway.refresh();
            var board = gateway.overview().data();
            assertEquals("CONNECTED", board.gatewayState());
            var quotes = board.quotes();
            assertEquals(2, quotes.size());
            assertEquals(100, quotes.stream().filter(quote -> quote.id().equals("cbot-corn"))
                    .findFirst().orElseThrow().last().intValueExact());
            assertEquals(99, quotes.stream().filter(quote -> quote.id().equals("dxy"))
                    .findFirst().orElseThrow().last().intValueExact());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void sameSourceTimeWithConflictingValueNeverOverwritesAnAcceptedTick() throws Exception {
        Instant sourceAt = Instant.now().minusSeconds(5);
        String first = "{\"quotes\":[{\"id\":\"cbot-corn\",\"last\":100,"
                + "\"sourceAt\":\"" + sourceAt + "\",\"provider\":\"licensed-test\"}]}";
        String conflict = first.replace("\"last\":100", "\"last\":101");
        withFeed(envelope("RECONCILED", Instant.now(), first), (gateway, payload) -> {
            gateway.refresh();
            assertEquals("CONNECTED", gateway.overview().data().gatewayState());
            payload.set(envelope("RECONCILED", Instant.now(), conflict));
            gateway.refresh();
            var board = gateway.overview().data();
            assertEquals("SOURCE_ERROR", board.gatewayState());
            assertEquals("QUOTE_FEED_TIMESTAMP_CONFLICT", board.lastError());
            assertEquals(100, board.quotes().getFirst().last().intValueExact());
            payload.set(envelope("RECONCILED", Instant.now(), first));
            gateway.refresh();
            assertEquals("CONNECTED", gateway.overview().data().gatewayState());
            assertEquals(100, gateway.overview().data().quotes().getFirst().last().intValueExact());
        });
    }

    @Test
    void conflictingRowsWithinOneEnvelopeRejectThatEnvelope() throws Exception {
        Instant sourceAt = Instant.now().minusSeconds(5);
        String row = "{\"id\":\"cbot-corn\",\"last\":100,\"sourceAt\":\""
                + sourceAt + "\",\"provider\":\"licensed-test\"}";
        String body = "{\"quotes\":[" + row + ","
                + row.replace("\"last\":100", "\"last\":101") + "]}";
        withFeed(envelope("RECONCILED", Instant.now(), body), (gateway, payload) -> {
            gateway.refresh();
            var board = gateway.overview().data();
            assertEquals("SOURCE_ERROR", board.gatewayState());
            assertEquals("QUOTE_FEED_TIMESTAMP_CONFLICT", board.lastError());
            assertTrue(board.quotes().isEmpty());
        });
    }

    @Test
    void grainQuotesRejectMissingOrMismatchedContractIdentity() throws Exception {
        Instant now = Instant.now();
        var invalidRows = java.util.List.of(
                "\"id\":\"dce-corn\",\"last\":2000",
                "\"id\":\"dce-corn\",\"last\":2000,\"exchangeId\":\"CZCE\",\"productId\":\"c\",\"contractId\":\"DCE.c2601\",\"unit\":\"元/吨\"",
                "\"id\":\"dce-corn\",\"last\":2000,\"exchangeId\":\"DCE\",\"productId\":\"a\",\"contractId\":\"DCE.a2601\",\"unit\":\"元/吨\"",
                "\"id\":\"dce-corn\",\"last\":2000,\"exchangeId\":\"DCE\",\"productId\":\"c\",\"contractId\":\"KQ.m@DCE.c\",\"unit\":\"元/吨\"",
                "\"id\":\"dce-corn\",\"last\":2000,\"exchangeId\":\"DCE\",\"productId\":\"c\",\"contractId\":\"DCE.c2600\",\"unit\":\"元/吨\"",
                "\"id\":\"dce-corn\",\"last\":2000,\"exchangeId\":\"DCE\",\"productId\":\"c\",\"contractId\":\"DCE.c2601\",\"unit\":\"美分/蒲式耳\""
        );
        withFeed(envelope("RECONCILED", now, currentQuote()), (gateway, payload) -> {
            for (String row : invalidRows) {
                payload.set(envelope("RECONCILED", Instant.now(), "{\"quotes\":[{"
                        + row + ",\"sourceAt\":\"" + now + "\",\"provider\":\"synthetic-test\"}]}"));
                gateway.refresh();
                assertTrue(gateway.overview().data().quotes().isEmpty());
                assertEquals("SOURCE_ERROR", gateway.overview().data().gatewayState());
            }
        });
    }

    @Test
    void grainQuotesPreserveValidatedActualContracts() throws Exception {
        Instant now = Instant.now();
        String body = "{\"quotes\":["
                + grainRow("dce-soybean", "DCE", "a", "DCE.a2601", now) + ","
                + grainRow("dce-corn", "DCE", "c", "DCE.c2601", now) + ","
                + grainRow("czce-wheat", "CZCE", "WH", "CZCE.WH601", now) + ","
                + grainRow("czce-common-wheat", "CZCE", "PM", "CZCE.PM601", now) + ","
                + grainRow("czce-rice", "CZCE", "LR", "CZCE.LR601", now) + "]}";
        withFeed(envelope("RECONCILED", now, body), (gateway, payload) -> {
            gateway.refresh();
            var board = gateway.overview().data();
            assertEquals("CONNECTED", board.gatewayState());
            assertEquals(5, board.quotes().size());
            assertEquals("DCE.c2601", board.quotes().stream()
                    .filter(q -> "dce-corn".equals(q.id())).findFirst().orElseThrow().contractId());
            assertEquals("CZCE.LR601", board.quotes().stream()
                    .filter(q -> "czce-rice".equals(q.id())).findFirst().orElseThrow().contractId());
        });
    }

    private static String grainRow(String id, String exchangeId, String productId,
                                   String contractId, Instant sourceAt) {
        return "{\"id\":\"" + id + "\",\"last\":2000,\"sourceAt\":\"" + sourceAt
                + "\",\"provider\":\"synthetic-test\",\"exchangeId\":\"" + exchangeId
                + "\",\"productId\":\"" + productId + "\",\"contractId\":\"" + contractId
                + "\",\"unit\":\"元/吨\"}";
    }
}
