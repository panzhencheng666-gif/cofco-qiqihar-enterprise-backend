package com.cofco.qiqihar.graintrade.notification.interfaceadapter;

import static org.assertj.core.api.Assertions.assertThat;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
    private static final String VISIBLE_REGION = "230200";
    private static final String HIDDEN_REGION = "231100";
    private static final UUID FIRST_VISIBLE = UUID.fromString("00000000-0000-0000-0000-000000000073");
    private static final UUID HIDDEN = UUID.fromString("00000000-0000-0000-0000-000000000074");
    private static final UUID SECOND_VISIBLE = UUID.fromString("00000000-0000-0000-0000-000000000075");

    @LocalServerPort int port;
    @Autowired DataSource dataSource;
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
                  jsonb_build_object('regionCode',:regionCode))
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
                    .doesNotContain(FIRST_VISIBLE.toString())
                    .doesNotContain(HIDDEN.toString())
                    .doesNotContain("stream-market-hidden");
        }
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

    private void cleanup() {
        if (jdbc != null) {
            jdbc.sql("DELETE FROM platform.business_event_outbox WHERE event_id IN (:ids)")
                    .param("ids", List.of(FIRST_VISIBLE, HIDDEN, SECOND_VISIBLE)).update();
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
