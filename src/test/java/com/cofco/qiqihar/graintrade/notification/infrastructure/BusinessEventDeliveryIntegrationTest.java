package com.cofco.qiqihar.graintrade.notification.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.notification.application.BusinessEventDeliveryRepository;
import com.cofco.qiqihar.graintrade.notification.application.BusinessEventDeliveryService;
import com.cofco.qiqihar.graintrade.notification.application.BusinessNotificationRepository;
import com.cofco.qiqihar.graintrade.shared.security.application.AuthorizedReadScope;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(classes = GrainTradeApplication.class)
@UsesProtectedTestDatabase
class BusinessEventDeliveryIntegrationTest {
    private static final String CONSUMER = "def-100-shared-consumer";
    private static final String SUBJECT = "def-100-reader";
    private static final String REGION = "230208";
    private static final UUID FIRST = UUID.fromString("99000000-0000-0000-0000-000000000001");
    private static final UUID POISON = UUID.fromString("99000000-0000-0000-0000-000000000002");
    private static final UUID THIRD = UUID.fromString("99000000-0000-0000-0000-000000000003");

    @Autowired DataSource dataSource;
    @Autowired BusinessNotificationRepository notifications;
    @Autowired BusinessEventDeliveryRepository deliveries;
    private JdbcClient jdbc;
    private MutableClock clock;
    private long initialSequence;

    @BeforeEach
    void setUp() {
        jdbc = JdbcClient.create(dataSource);
        cleanup();
        clock = new MutableClock(Instant.parse("2026-08-12T12:00:00Z"));
        insertEvent(FIRST, "first", clock.instant());
        insertEvent(POISON, "poison", clock.instant().plusSeconds(1));
        insertEvent(THIRD, "third", clock.instant().plusSeconds(2));
        initialSequence = jdbc.sql("""
                SELECT min(event_sequence)-1 FROM platform.business_event_outbox
                WHERE event_id IN (:ids)
                """).param("ids", List.of(FIRST, POISON, THIRD)).query(Long.class).single();
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    void twoServicesConcurrentlyIsolateOneFailureThenRecoverWithoutLossOrDuplicateSideEffects()
            throws Exception {
        BusinessEventDeliveryService firstService = service();
        BusinessEventDeliveryService secondService = service();
        AuthorizedReadScope scope = new AuthorizedReadScope(SUBJECT, Set.of(REGION));
        AtomicBoolean failPoisonOnce = new AtomicBoolean(true);
        CountDownLatch bothServicesReachedSink = new CountDownLatch(2);
        ConcurrentHashMap<UUID, Integer> sideEffects = new ConcurrentHashMap<>();
        BusinessEventDeliveryService.DeliverySink sink = event -> {
            if (event.id().equals(POISON) && failPoisonOnce.compareAndSet(true, false)) {
                throw new IllegalStateException("injected single-event delivery failure");
            }
            if (bothServicesReachedSink.getCount() > 0) {
                bothServicesReachedSink.countDown();
                assertThat(bothServicesReachedSink.await(2, TimeUnit.SECONDS)).isTrue();
            }
            sideEffects.merge(event.id(), 1, Integer::sum);
        };

        drainConcurrently(firstService, secondService, scope, sink);

        assertThat(sideEffects).containsEntry(FIRST, 1).containsEntry(THIRD, 1)
                .doesNotContainKey(POISON);
        assertThat(firstService.backlog(CONSUMER, scope, initialSequence))
                .satisfies(backlog -> {
                    assertThat(backlog.pendingCount()).isEqualTo(1);
                    assertThat(backlog.retryScheduledCount()).isEqualTo(1);
                    assertThat(backlog.inProgressCount()).isZero();
                    assertThat(backlog.quarantinedCount()).isZero();
                    assertThat(backlog.oldestPendingAt()).isEqualTo(clock.instant().plusSeconds(1));
                });
        assertThat(status(POISON)).isEqualTo("RETRY_SCHEDULED");
        assertThat(attemptStatuses(POISON)).containsExactly("RETRY_SCHEDULED");
        assertThat(jdbc.sql("""
                SELECT pending_count,retry_scheduled_count,oldest_pending_at
                FROM platform.business_event_delivery_backlog WHERE consumer_id=:consumer
                """).param("consumer", CONSUMER).query().singleRow()).satisfies(row -> {
                    assertThat(((Number) row.get("pending_count")).longValue()).isEqualTo(1);
                    assertThat(((Number) row.get("retry_scheduled_count")).longValue()).isEqualTo(1);
                    assertThat(((Timestamp) row.get("oldest_pending_at")).toInstant())
                            .isEqualTo(clock.instant().plusSeconds(1));
                });

        clock.advance(Duration.ofMillis(101));
        drainConcurrently(firstService, secondService, scope, sink);
        drainConcurrently(firstService, secondService, scope, sink);

        assertThat(sideEffects).containsEntry(FIRST, 1).containsEntry(POISON, 1).containsEntry(THIRD, 1);
        assertThat(firstService.backlog(CONSUMER, scope, initialSequence))
                .satisfies(backlog -> {
                    assertThat(backlog.pendingCount()).isZero();
                    assertThat(backlog.retryScheduledCount()).isZero();
                    assertThat(backlog.inProgressCount()).isZero();
                    assertThat(backlog.quarantinedCount()).isZero();
                    assertThat(backlog.oldestPendingAt()).isNull();
                });
        assertThat(status(POISON)).isEqualTo("DELIVERED");
        assertThat(attemptStatuses(POISON)).containsExactly("RETRY_SCHEDULED", "DELIVERED");
        assertThat(jdbc.sql("""
                SELECT pending_count,oldest_pending_at
                FROM platform.business_event_delivery_backlog WHERE consumer_id=:consumer
                """).param("consumer", CONSUMER).query().singleRow()).satisfies(row -> {
                    assertThat(((Number) row.get("pending_count")).longValue()).isZero();
                    assertThat(row.get("oldest_pending_at")).isNull();
                });
        assertThat(jdbc.sql("""
                SELECT delivered_count FROM platform.business_event_delivery_checkpoint
                WHERE consumer_id=:consumer
                """).param("consumer", CONSUMER).query(Long.class).single()).isEqualTo(3);
        assertThat(jdbc.sql("""
                SELECT count(DISTINCT instance_id) FROM platform.business_event_delivery_attempt
                WHERE consumer_id=:consumer
                """).param("consumer", CONSUMER).query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void queryFailureUsesPersistentBoundedBackoffAndRecordsRecovery() {
        BusinessNotificationRepository failingNotifications = mock(BusinessNotificationRepository.class);
        AuthorizedReadScope scope = new AuthorizedReadScope(SUBJECT, Set.of(REGION));
        when(failingNotifications.findVisibleAfter(scope, SUBJECT, initialSequence, 20))
                .thenThrow(new IllegalStateException("database temporarily unavailable"))
                .thenReturn(List.of());
        BusinessEventDeliveryService service = new BusinessEventDeliveryService(
                failingNotifications, deliveries, clock, Duration.ofSeconds(1),
                Duration.ofMillis(100), Duration.ofMillis(400), 3);

        var failed = service.drain(CONSUMER, "instance-query", scope, SUBJECT,
                initialSequence, 20, ignored -> {});
        var throttled = service.drain(CONSUMER, "instance-query", scope, SUBJECT,
                initialSequence, 20, ignored -> {});

        assertThat(failed.queryFailed()).isTrue();
        assertThat(failed.retryAfter()).isEqualTo(Duration.ofMillis(100));
        assertThat(throttled.queryFailed()).isFalse();
        verify(failingNotifications, times(1))
                .findVisibleAfter(scope, SUBJECT, initialSequence, 20);
        assertThat(jdbc.sql("""
                SELECT status_code,failure_code FROM platform.business_event_poll_attempt
                WHERE consumer_id=:consumer ORDER BY poll_attempt_id
                """).param("consumer", CONSUMER).query().listOfRows())
                .singleElement().satisfies(row -> {
                    assertThat(row).containsEntry("status_code", "RETRY_SCHEDULED");
                    assertThat(row).containsEntry("failure_code", "EVENT_QUERY_FAILED");
                });

        clock.advance(Duration.ofMillis(101));
        var recovered = service.drain(CONSUMER, "instance-query", scope, SUBJECT,
                initialSequence, 20, ignored -> {});

        assertThat(recovered.queryFailed()).isFalse();
        verify(failingNotifications, times(2))
                .findVisibleAfter(scope, SUBJECT, initialSequence, 20);
        assertThat(jdbc.sql("""
                SELECT status_code FROM platform.business_event_poll_attempt
                WHERE consumer_id=:consumer ORDER BY poll_attempt_id
                """).param("consumer", CONSUMER).query(String.class).list())
                .containsExactly("RETRY_SCHEDULED", "SUCCEEDED");
        assertThat(jdbc.sql("""
                SELECT consecutive_poll_failures FROM platform.business_event_delivery_checkpoint
                WHERE consumer_id=:consumer
                """).param("consumer", CONSUMER).query(Integer.class).single()).isZero();
    }

    @Test
    void repeatedlyFailingEventIsQuarantinedAtTheBoundWithoutBlockingAdjacentEvents() {
        BusinessEventDeliveryService service = service();
        AuthorizedReadScope scope = new AuthorizedReadScope(SUBJECT, Set.of(REGION));
        ConcurrentHashMap<UUID, Integer> sideEffects = new ConcurrentHashMap<>();
        BusinessEventDeliveryService.DeliverySink sink = event -> {
            if (event.id().equals(POISON)) {
                throw new IllegalStateException("persistent poison event");
            }
            sideEffects.merge(event.id(), 1, Integer::sum);
        };

        service.drain(CONSUMER, "instance-quarantine", scope, SUBJECT, initialSequence, 20, sink);
        clock.advance(Duration.ofMillis(101));
        service.drain(CONSUMER, "instance-quarantine", scope, SUBJECT, initialSequence, 20, sink);
        clock.advance(Duration.ofMillis(201));
        service.drain(CONSUMER, "instance-quarantine", scope, SUBJECT, initialSequence, 20, sink);
        service.drain(CONSUMER, "instance-quarantine", scope, SUBJECT, initialSequence, 20, sink);

        assertThat(sideEffects).containsEntry(FIRST, 1).containsEntry(THIRD, 1)
                .doesNotContainKey(POISON);
        assertThat(status(POISON)).isEqualTo("QUARANTINED");
        assertThat(attemptStatuses(POISON))
                .containsExactly("RETRY_SCHEDULED", "RETRY_SCHEDULED", "QUARANTINED");
        assertThat(service.backlog(CONSUMER, scope, initialSequence)).satisfies(backlog -> {
            assertThat(backlog.pendingCount()).isZero();
            assertThat(backlog.retryScheduledCount()).isZero();
            assertThat(backlog.quarantinedCount()).isEqualTo(1);
            assertThat(backlog.oldestPendingAt()).isNull();
        });
        assertThat(jdbc.sql("""
                SELECT quarantined_count FROM platform.business_event_delivery_checkpoint
                WHERE consumer_id=:consumer
                """).param("consumer", CONSUMER).query(Long.class).single()).isEqualTo(1);
    }

    private BusinessEventDeliveryService service() {
        return new BusinessEventDeliveryService(notifications, deliveries, clock,
                Duration.ofSeconds(1), Duration.ofMillis(100), Duration.ofMillis(400), 3);
    }

    private void drainConcurrently(
            BusinessEventDeliveryService firstService,
            BusinessEventDeliveryService secondService,
            AuthorizedReadScope scope,
            BusinessEventDeliveryService.DeliverySink sink) throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            var results = executor.invokeAll(List.of(
                    () -> firstService.drain(CONSUMER, "instance-a", scope, SUBJECT,
                            initialSequence, 20, sink),
                    () -> secondService.drain(CONSUMER, "instance-b", scope, SUBJECT,
                            initialSequence, 20, sink)));
            for (var result : results) result.get();
        }
    }

    private void insertEvent(UUID id, String aggregateId, Instant occurredAt) {
        jdbc.sql("""
                INSERT INTO platform.business_event_outbox(
                  event_id,aggregate_type,aggregate_id,action_code,actor_subject_id,
                  work_unit_code,region_codes,product_code,occurred_at,detail)
                VALUES(:id,'DELIVERY_PROBE',:aggregateId,'DELIVERY_PROBE_CREATED','production-tester',
                  'DEF_100_TEST',ARRAY[:region],'CORN',:occurredAt,
                  jsonb_build_object('regionCode',:region,'productCode','CORN','surveyYear',2026))
                """).param("id", id).param("aggregateId", aggregateId).param("region", REGION)
                .param("occurredAt", Timestamp.from(occurredAt)).update();
    }

    private String status(UUID eventId) {
        return jdbc.sql("""
                SELECT status_code FROM platform.business_event_delivery_state
                WHERE consumer_id=:consumer AND event_id=:eventId
                """).param("consumer", CONSUMER).param("eventId", eventId)
                .query(String.class).single();
    }

    private List<String> attemptStatuses(UUID eventId) {
        return jdbc.sql("""
                SELECT status_code FROM platform.business_event_delivery_attempt
                WHERE consumer_id=:consumer AND event_id=:eventId ORDER BY attempt_no
                """).param("consumer", CONSUMER).param("eventId", eventId)
                .query(String.class).list();
    }

    private void cleanup() {
        if (jdbc == null) return;
        jdbc.sql("DELETE FROM platform.business_event_delivery_checkpoint WHERE consumer_id=:consumer")
                .param("consumer", CONSUMER).update();
        jdbc.sql("DELETE FROM platform.business_event_outbox WHERE event_id IN (:ids)")
                .param("ids", List.of(FIRST, POISON, THIRD)).update();
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> current;

        private MutableClock(Instant initial) {
            current = new AtomicReference<>(initial);
        }

        void advance(Duration duration) {
            current.updateAndGet(instant -> instant.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current.get();
        }
    }
}
