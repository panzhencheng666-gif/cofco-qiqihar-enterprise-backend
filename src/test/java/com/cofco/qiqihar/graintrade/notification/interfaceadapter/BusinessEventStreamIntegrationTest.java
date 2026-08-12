package com.cofco.qiqihar.graintrade.notification.interfaceadapter;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.notification.application.BusinessNotificationRepository;
import com.cofco.qiqihar.graintrade.shared.security.application.AuthorizedReadScope;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(
        classes = GrainTradeApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "qiqihar.security.test-default-subject=business-event-reader")
@UsesProtectedTestDatabase
class BusinessEventStreamIntegrationTest {
    private static final String READER = "business-event-reader";
    private static final String WORK_UNIT = "BUSINESS_EVENT_STREAM_TEST";
    private static final String VISIBLE_REGION = "230208";
    private static final String HIDDEN_REGION = "231100";
    private static final UUID FIRST_VISIBLE = UUID.fromString("00000000-0000-0000-0000-000000000073");
    private static final UUID HIDDEN = UUID.fromString("00000000-0000-0000-0000-000000000074");
    private static final UUID SECOND_VISIBLE = UUID.fromString("00000000-0000-0000-0000-000000000075");
    private static final UUID DISCONNECT_TRIGGER = UUID.fromString("00000000-0000-0000-0000-000000000076");

    @LocalServerPort int port;
    @Autowired DataSource dataSource;
    @Autowired BusinessNotificationRepository notifications;
    private JdbcClient jdbc;

    @BeforeEach
    void insertVisibleEvents() {
        jdbc = JdbcClient.create(dataSource);
        cleanup();
        jdbc.sql("""
                INSERT INTO platform.work_unit(code,name,sort_order)
                VALUES(:unit,'实时事件测试单位',9980)
                """).param("unit", WORK_UNIT).update();
        jdbc.sql("""
                INSERT INTO platform.work_unit_region_scope(work_unit_code,region_code)
                VALUES(:unit,:region)
                """).param("unit", WORK_UNIT).param("region", VISIBLE_REGION).update();
        jdbc.sql("""
                INSERT INTO platform.security_user(subject_id,display_name,work_unit_code)
                VALUES(:reader,'实时事件测试员工',:unit)
                """).param("reader", READER).param("unit", WORK_UNIT).update();
        jdbc.sql("INSERT INTO platform.security_user_role(subject_id,role_code) VALUES(:reader,'SYSTEM_ADMIN')")
                .param("reader", READER).update();
        jdbc.sql("""
                INSERT INTO platform.security_user_region_scope(subject_id,region_code)
                VALUES(:reader,:region)
                """).param("reader", READER).param("region", VISIBLE_REGION).update();
        insertEvent(FIRST_VISIBLE, "stream-market-first", VISIBLE_REGION);
        insertEvent(HIDDEN, "stream-market-hidden", HIDDEN_REGION);
        insertEvent(SECOND_VISIBLE, "stream-market-second", VISIBLE_REGION);
    }

    private void insertEvent(UUID id, String aggregateId, String regionCode) {
        jdbc.sql("""
                INSERT INTO platform.business_event_outbox(
                  event_id,aggregate_type,aggregate_id,action_code,actor_subject_id,
                  work_unit_code,region_codes,product_code,occurred_at,detail)
                VALUES(:id,'MARKET_RECORD',:aggregateId,'MARKET_RECORD_CREATED',
                  'market-tester',:unit,ARRAY[:regionCode],'CORN',now(),
                  jsonb_build_object('regionCode',:regionCode,'productCode','CORN','surveyYear',2026))
                """).param("id", id).param("aggregateId", aggregateId)
                .param("unit", WORK_UNIT).param("regionCode", regionCode).update();
    }

    @AfterEach
    void removeVisibleEvents() {
        cleanup();
    }

    @Test
    void streamsAuthorizedDurableChangeWithReconnectCursor() throws Exception {
        long firstSequence = jdbc.sql("""
                SELECT event_sequence FROM platform.business_event_outbox WHERE event_id=:id
                """).param("id", FIRST_VISIBLE).query(Long.class).single();
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + port + "/api/v1/business-events/stream"))
                .header("Accept", "text/event-stream")
                .header("Last-Event-ID", Long.toString(firstSequence))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        HttpResponse<java.io.InputStream> response = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3)).build()
                .send(request, HttpResponse.BodyHandlers.ofInputStream());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
                .startsWith("text/event-stream");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                response.body(), StandardCharsets.UTF_8))) {
            StringBuilder frameBuilder = new StringBuilder();
            for (int lineCount = 0; lineCount < 10; lineCount++) {
                String line = reader.readLine();
                if (line == null || line.isBlank()) break;
                frameBuilder.append(line).append('\n');
            }
            String frame = frameBuilder.toString();
            assertThat(frame)
                    .contains("event:business-change")
                    .contains("\"id\":\"" + SECOND_VISIBLE + "\"")
                    .contains("\"aggregateId\":\"stream-market-second\"")
                    .contains("\"productCode\":\"CORN\"")
                    .contains("\"surveyYear\":2026")
                    .contains("\"regionCodes\":[\"230200\",\"230208\"]")
                    .doesNotContain(FIRST_VISIBLE.toString())
                    .doesNotContain(HIDDEN.toString())
                    .doesNotContain("stream-market-hidden");
        }
    }

    @Test
    void ancestorRefreshProjectionDoesNotWidenStoredRegionAuthorization() {
        assertThat(notifications.findVisible(
                        new AuthorizedReadScope(READER, Set.of("230200")), READER, 20))
                .noneMatch(notification -> notification.id().equals(FIRST_VISIBLE));
        assertThat(notifications.findVisible(
                        new AuthorizedReadScope(READER, Set.of(VISIBLE_REGION)), READER, 20))
                .filteredOn(notification -> notification.id().equals(FIRST_VISIBLE))
                .singleElement()
                .satisfies(notification -> assertThat(notification.regionCodes())
                        .containsExactly("230200", VISIBLE_REGION));
    }

    @Test
    void rejectsNegativeReconnectCursorWithStableBusinessError() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + port + "/api/v1/business-events/stream?after=-1"))
                .header("Accept", "application/json, text/event-stream")
                .GET()
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("\"code\":\"INVALID_EVENT_CURSOR\"");
    }

    @Test
    void clientDisconnectDoesNotFailAuthorizationDuringAsyncCompletion() throws Exception {
        Logger rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        rootLogger.addAppender(captured);
        try {
            long firstSequence = jdbc.sql("""
                    SELECT event_sequence FROM platform.business_event_outbox WHERE event_id=:id
                    """).param("id", FIRST_VISIBLE).query(Long.class).single();
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress("127.0.0.1", port), 3_000);
            socket.setSoTimeout(3_000);
            socket.getOutputStream().write(("GET /api/v1/business-events/stream?after="
                    + firstSequence + " HTTP/1.1\r\nHost: 127.0.0.1:" + port
                    + "\r\nAccept: text/event-stream\r\nConnection: keep-alive\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            StringBuilder received = new StringBuilder();
            while (!received.toString().contains("stream-market-second")) {
                int next = socket.getInputStream().read();
                if (next < 0) break;
                received.append((char) next);
            }
            assertThat(received).contains("HTTP/1.1 200").contains("stream-market-second");
            socket.setSoLinger(true, 0);
            socket.close();

            insertEvent(DISCONNECT_TRIGGER, "stream-disconnect-trigger", VISIBLE_REGION);
            long deadline = System.nanoTime() + Duration.ofSeconds(4).toNanos();
            while (System.nanoTime() < deadline && !containsAuthorizationDenied(captured)) {
                Thread.sleep(50);
            }

            assertThat(captured.list)
                    .noneMatch(BusinessEventStreamIntegrationTest::isAuthorizationDenied);
        } finally {
            rootLogger.detachAppender(captured);
            captured.stop();
        }
    }

    private static boolean containsAuthorizationDenied(ListAppender<ILoggingEvent> captured) {
        return captured.list.stream().anyMatch(BusinessEventStreamIntegrationTest::isAuthorizationDenied);
    }

    private static boolean isAuthorizationDenied(ILoggingEvent event) {
        return event.getThrowableProxy() != null
                && ThrowableProxyUtil.asString(event.getThrowableProxy())
                        .contains("AuthorizationDeniedException");
    }

    private void cleanup() {
        if (jdbc != null) {
            jdbc.sql("""
                    DELETE FROM platform.business_event_consumer_lifecycle_event lifecycle
                    WHERE EXISTS (
                      SELECT 1 FROM platform.business_event_delivery_checkpoint checkpoint
                      WHERE checkpoint.consumer_id=lifecycle.consumer_id
                        AND checkpoint.authorization_subject_id=:reader)
                    """).param("reader", READER).update();
            jdbc.sql("""
                    DELETE FROM platform.business_event_delivery_checkpoint
                    WHERE authorization_subject_id=:reader
                    """).param("reader", READER).update();
            jdbc.sql("DELETE FROM platform.business_event_outbox WHERE work_unit_code=:unit")
                    .param("unit", WORK_UNIT).update();
            jdbc.sql("DELETE FROM platform.security_user_region_scope WHERE subject_id=:reader")
                    .param("reader", READER).update();
            jdbc.sql("DELETE FROM platform.security_user_role WHERE subject_id=:reader")
                    .param("reader", READER).update();
            jdbc.sql("DELETE FROM platform.security_user WHERE subject_id=:reader")
                    .param("reader", READER).update();
            jdbc.sql("DELETE FROM platform.work_unit_region_scope WHERE work_unit_code=:unit")
                    .param("unit", WORK_UNIT).update();
            jdbc.sql("DELETE FROM platform.work_unit WHERE code=:unit")
                    .param("unit", WORK_UNIT).update();
        }
    }
}
