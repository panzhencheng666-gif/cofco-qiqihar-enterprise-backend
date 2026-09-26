package com.cofco.qiqihar.graintrade.marketintelligence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Real loopback HTTP and Python process, synthetic SDK; never a vendor acceptance test. */
class ChoiceFeedProcessTest {
    private static class Fixture implements AutoCloseable {
        final String token = UUID.randomUUID().toString();
        final Process process;
        final PrintWriter commands;
        final LinkedBlockingQueue<String> responses = new LinkedBlockingQueue<>(64);

        Fixture() throws Exception {
            var builder = new ProcessBuilder("python3", "-u",
                    "scripts/tests/choice_feed_process_fixture.py", "--synthetic-test-only");
            builder.environment().put("CHOICE_TEST_FEED_TOKEN", token);
            builder.environment().put("PYTHONDONTWRITEBYTECODE", "1");
            builder.redirectError(ProcessBuilder.Redirect.INHERIT);
            process = builder.start();
            commands = new PrintWriter(process.getOutputStream(), true, StandardCharsets.UTF_8);
            Thread reader = new Thread(() -> {
                try (var lines = new BufferedReader(new InputStreamReader(
                        process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = lines.readLine()) != null) responses.offer(line);
                } catch (Exception ignored) {
                    // Failure is reported as a bounded handshake timeout, without private process output.
                }
            }, "choice-test-handshake");
            reader.setDaemon(true);
            reader.start();
        }

        JsonNode await(String event) throws Exception {
            String response = responses.poll(5, TimeUnit.SECONDS);
            assertNotNull(response, "Synthetic feed process did not acknowledge within five seconds");
            JsonNode json = new ObjectMapper().readTree(response);
            assertEquals(event, json.path("event").asText());
            return json;
        }

        void command(String command) throws Exception {
            commands.println(command);
            await(command);
        }

        @Override public void close() throws Exception {
            commands.println("quit");
            commands.close();
            if (!process.waitFor(4, TimeUnit.SECONDS)) {
                process.destroy();
                if (!process.waitFor(1, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(1, TimeUnit.SECONDS);
                }
                assertFalse(process.isAlive(), "Owned synthetic fixture was not stopped");
                throw new AssertionError("Synthetic fixture needed forced shutdown");
            }
            assertEquals(0, process.exitValue(), "Synthetic fixture failed");
        }
    }

    @Test
    void periodicPythonPublicationReachesJavaAndPermissionLossClearsIt() throws Exception {
        try (var fixture = new Fixture()) {
            int port = fixture.await("ready").path("port").asInt();
            String url = "http://127.0.0.1:" + port + "/quotes";
            var wrongToken = new MarketQuoteGateway(new ObjectMapper(), url, "invalid-test-token", true);
            wrongToken.refresh();
            assertEquals("SOURCE_ERROR", wrongToken.overview().data().gatewayState());
            assertTrue(wrongToken.overview().data().quotes().isEmpty());
            var gateway = new MarketQuoteGateway(new ObjectMapper(), url, fixture.token, true);
            gateway.refresh();
            var first = gateway.overview().data();
            assertEquals("CONNECTED", first.gatewayState());
            assertEquals(2000, first.quotes().getFirst().last().intValueExact());
            fixture.command("tick");
            gateway.refresh();
            var changed = gateway.overview().data();
            assertEquals(2100, changed.quotes().getFirst().last().intValueExact());
            assertTrue(changed.quotes().getFirst().sourceAt().isAfter(first.quotes().getFirst().sourceAt()));
            fixture.command("denied");
            gateway.refresh();
            assertEquals("ENTITLEMENT_ERROR", gateway.overview().data().feedState());
            assertEquals("PENDING_AUTHORIZATION", gateway.overview().data().gatewayState());
            assertTrue(gateway.overview().data().quotes().isEmpty());
        }
    }

    @Test
    void frozenWorkerDoesNotRenewHeartbeatThroughHttpAndCanRecover() throws Exception {
        var offset = new AtomicReference<>(Duration.ZERO);
        Clock clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return Instant.now().plus(offset.get()); }
        };
        try (var fixture = new Fixture()) {
            int port = fixture.await("ready").path("port").asInt();
            var gateway = new MarketQuoteGateway(new ObjectMapper(),
                    "http://127.0.0.1:" + port + "/quotes", fixture.token, true, clock);
            gateway.refresh();
            assertEquals("CONNECTED", gateway.overview().data().gatewayState());
            fixture.command("freeze");
            gateway.refresh();
            var frozen = gateway.overview().data();
            offset.set(Duration.ofSeconds(31));
            gateway.refresh();
            var expired = gateway.overview().data();
            assertEquals("SOURCE_ERROR", expired.gatewayState());
            assertEquals("QUOTE_FEED_HEARTBEAT_STALE", expired.lastError());
            assertEquals(frozen.feedPublishedAt(), expired.feedPublishedAt());
            assertEquals(frozen.quotes().getFirst().sourceAt(), expired.quotes().getFirst().sourceAt());
            offset.set(Duration.ZERO);
            fixture.command("resume");
            gateway.refresh();
            assertEquals("CONNECTED", gateway.overview().data().gatewayState());
        }
    }
}
