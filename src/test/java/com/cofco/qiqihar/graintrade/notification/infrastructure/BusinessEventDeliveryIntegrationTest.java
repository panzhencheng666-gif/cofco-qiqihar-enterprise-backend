package com.cofco.qiqihar.graintrade.notification.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.notification.application.BusinessEventDeliveryRepository;
import com.cofco.qiqihar.graintrade.notification.application.BusinessEventDeliveryService;
import com.cofco.qiqihar.graintrade.notification.application.BusinessNotificationRepository;
import com.cofco.qiqihar.graintrade.notification.application.ConsumerRetirementReason;
import com.cofco.qiqihar.graintrade.shared.security.application.AuthorizedReadScope;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import java.sql.Connection;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Callable;
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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.core.annotation.AnnotatedElementUtils;

@SpringBootTest(classes = GrainTradeApplication.class)
@UsesProtectedTestDatabase
class BusinessEventDeliveryIntegrationTest {
    private static final String CONSUMER = "def-100-shared-consumer";
    private static final String SUBJECT = "def-100-reader";
    private static final String FULL_SCOPE_SUBJECT = "def-103-full-scope-reader";
    private static final String EMPTY_SCOPE_SUBJECT = "def-103-empty-scope-reader";
    private static final String REGION = "230208";
    private static final String HIDDEN_REGION = "231100";
    private static final UUID FIRST = UUID.fromString("99000000-0000-0000-0000-000000000001");
    private static final UUID POISON = UUID.fromString("99000000-0000-0000-0000-000000000002");
    private static final UUID THIRD = UUID.fromString("99000000-0000-0000-0000-000000000003");
    private static final UUID HIDDEN = UUID.fromString("99000000-0000-0000-0000-000000000004");
    private static final UUID HIDDEN_BACKLOG =
            UUID.fromString("99000000-0000-0000-0000-000000000005");
    private static final UUID VISIBLE_BACKLOG =
            UUID.fromString("99000000-0000-0000-0000-000000000006");

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
        ensureAuthorizationFixture();
        clock = new MutableClock(Instant.now().truncatedTo(ChronoUnit.MICROS));
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
    void independentStreamConsumersEachReceiveOnceAndOneFailureDoesNotSuppressTheOther()
            throws Exception {
        String firstConsumer = CONSUMER + ":session-a";
        String secondConsumer = CONSUMER + ":session-b";
        insertEvent(HIDDEN, "hidden", clock.instant().plusSeconds(3), HIDDEN_REGION);
        BusinessEventDeliveryService firstService = service();
        BusinessEventDeliveryService secondService = service();
        AuthorizedReadScope scope = new AuthorizedReadScope(SUBJECT, Set.of(REGION));
        AtomicBoolean failPoisonOnce = new AtomicBoolean(true);
        ConcurrentHashMap<UUID, Integer> firstEmitter = new ConcurrentHashMap<>();
        ConcurrentHashMap<UUID, Integer> secondEmitter = new ConcurrentHashMap<>();
        BusinessEventDeliveryService.DeliverySink firstSink = event -> {
            if (event.id().equals(POISON) && failPoisonOnce.compareAndSet(true, false)) {
                throw new IllegalStateException("first emitter disconnected during one event");
            }
            firstEmitter.merge(event.id(), 1, Integer::sum);
        };
        BusinessEventDeliveryService.DeliverySink secondSink =
                event -> secondEmitter.merge(event.id(), 1, Integer::sum);

        BusinessEventDeliveryService.DrainResult firstResult;
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<java.util.concurrent.Callable<BusinessEventDeliveryService.DrainResult>> drains = List.of(
                    () -> firstService.drain(firstConsumer, "instance-a", scope, SUBJECT,
                            initialSequence, 20, firstSink),
                    () -> secondService.drain(secondConsumer, "instance-b", scope, SUBJECT,
                            initialSequence, 20, secondSink));
            var results = executor.invokeAll(drains);
            firstResult = results.getFirst().get();
            results.get(1).get();
        }

        assertThat(firstEmitter)
                .containsEntry(FIRST, 1)
                .containsEntry(THIRD, 1)
                .doesNotContainKeys(POISON, HIDDEN);
        assertThat(secondEmitter)
                .containsEntry(FIRST, 1)
                .containsEntry(POISON, 1)
                .containsEntry(THIRD, 1)
                .doesNotContainKey(HIDDEN);

        clock.advance(Duration.ofMillis(101));
        var reconnected = firstService.drain(firstConsumer, "instance-a-reconnected", scope, SUBJECT,
                firstResult.resumeSequence(), 20, firstSink);

        assertThat(reconnected.failedCount()).isZero();
        assertThat(firstEmitter)
                .containsEntry(FIRST, 1)
                .containsEntry(POISON, 1)
                .containsEntry(THIRD, 1)
                .doesNotContainKey(HIDDEN);
        assertThat(secondEmitter)
                .containsEntry(FIRST, 1)
                .containsEntry(POISON, 1)
                .containsEntry(THIRD, 1)
                .doesNotContainKey(HIDDEN);
        assertThat(jdbc.sql("""
                SELECT consumer_id,count(*) AS delivered_count
                FROM platform.business_event_delivery_state
                WHERE consumer_id IN (:consumers) AND status_code='DELIVERED'
                GROUP BY consumer_id ORDER BY consumer_id
                """).param("consumers", List.of(firstConsumer, secondConsumer)).query().listOfRows())
                .satisfiesExactly(
                        row -> assertThat(row).containsEntry("consumer_id", firstConsumer)
                                .containsEntry("delivered_count", 3L),
                        row -> assertThat(row).containsEntry("consumer_id", secondConsumer)
                                .containsEntry("delivered_count", 3L));
        assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.business_event_delivery_state
                WHERE consumer_id IN (:consumers) AND event_id=:hidden
                """).param("consumers", List.of(firstConsumer, secondConsumer)).param("hidden", HIDDEN)
                .query(Long.class).single()).isZero();
    }

    @Test
    void operationalBacklogExcludesHiddenEventsWithoutDeliveryStateOrMetadataLeak() {
        String consumer = CONSUMER + ":authorized-backlog";
        AuthorizedReadScope scope = new AuthorizedReadScope(SUBJECT, Set.of(REGION));
        BusinessEventDeliveryService service = service();
        service.drain(consumer, "instance-authorized-backlog", scope, SUBJECT,
                initialSequence, 20, ignored -> {});
        insertEvent(HIDDEN_BACKLOG, "hidden-backlog", clock.instant().plusSeconds(3), HIDDEN_REGION);

        assertThat(service.backlog(consumer, scope, initialSequence)).satisfies(backlog -> {
            assertThat(backlog.pendingCount()).isZero();
            assertThat(backlog.oldestPendingAt()).isNull();
        });
        assertThat(jdbc.sql("""
                SELECT pending_count,oldest_pending_at
                FROM platform.business_event_delivery_backlog
                WHERE consumer_id=:consumer
                """).param("consumer", consumer).query().singleRow()).satisfies(row -> {
                    assertThat(((Number) row.get("pending_count")).longValue()).isZero();
                    assertThat(row.get("oldest_pending_at")).isNull();
                });
        assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.business_event_delivery_state
                WHERE consumer_id=:consumer AND event_id=:eventId
                """).param("consumer", consumer).param("eventId", HIDDEN_BACKLOG)
                .query(Long.class).single()).isZero();

        insertEvent(VISIBLE_BACKLOG, "visible-backlog", clock.instant().plusSeconds(4), REGION);

        assertThat(service.backlog(consumer, scope, initialSequence)).satisfies(backlog -> {
            assertThat(backlog.pendingCount()).isOne();
            assertThat(backlog.oldestPendingAt()).isEqualTo(clock.instant().plusSeconds(4));
        });
        assertThat(jdbc.sql("""
                SELECT pending_count,oldest_pending_at
                FROM platform.business_event_delivery_backlog
                WHERE consumer_id=:consumer
                """).param("consumer", consumer).query().singleRow()).satisfies(row -> {
                    assertThat(((Number) row.get("pending_count")).longValue()).isOne();
                    assertThat(((Timestamp) row.get("oldest_pending_at")).toInstant())
                            .isEqualTo(clock.instant().plusSeconds(4));
                });
    }

    @Test
    void operationalBacklogHandlesFullAndEmptyRegionScopesAndCurrentRevocation() {
        String fullConsumer = CONSUMER + ":full-scope";
        String emptyConsumer = CONSUMER + ":empty-scope";
        BusinessEventDeliveryService service = service();
        AuthorizedReadScope fullScope = new AuthorizedReadScope(
                FULL_SCOPE_SUBJECT, Set.of(REGION, HIDDEN_REGION));
        AuthorizedReadScope emptyScope = new AuthorizedReadScope(EMPTY_SCOPE_SUBJECT, Set.of());
        service.drain(fullConsumer, "instance-full-scope", fullScope, FULL_SCOPE_SUBJECT,
                initialSequence, 20, ignored -> {});
        service.drain(emptyConsumer, "instance-empty-scope", emptyScope, EMPTY_SCOPE_SUBJECT,
                initialSequence, 20, ignored -> {});
        insertEvent(HIDDEN_BACKLOG, "full-scope-hidden", clock.instant().plusSeconds(3), HIDDEN_REGION);
        insertEvent(VISIBLE_BACKLOG, "full-scope-visible", clock.instant().plusSeconds(4), REGION);

        assertThat(jdbc.sql("""
                SELECT consumer_id,pending_count,oldest_pending_at
                FROM platform.business_event_delivery_backlog
                WHERE consumer_id IN (:consumers)
                ORDER BY consumer_id
                """).param("consumers", List.of(fullConsumer, emptyConsumer)).query().listOfRows())
                .satisfiesExactly(
                        row -> {
                            assertThat(row).containsEntry("consumer_id", emptyConsumer)
                                    .containsEntry("pending_count", 0L);
                            assertThat(row.get("oldest_pending_at")).isNull();
                        },
                        row -> {
                            assertThat(row).containsEntry("consumer_id", fullConsumer)
                                    .containsEntry("pending_count", 2L);
                            assertThat(((Timestamp) row.get("oldest_pending_at")).toInstant())
                                    .isEqualTo(clock.instant().plusSeconds(3));
                        });

        jdbc.sql("""
                UPDATE platform.security_user_region_scope
                SET valid_until=clock_timestamp()
                WHERE subject_id=:subject AND region_code=:region
                """).param("subject", FULL_SCOPE_SUBJECT).param("region", HIDDEN_REGION).update();

        assertThat(jdbc.sql("""
                SELECT pending_count,oldest_pending_at
                FROM platform.business_event_delivery_backlog
                WHERE consumer_id=:consumer
                """).param("consumer", fullConsumer).query().singleRow()).satisfies(row -> {
                    assertThat(row).containsEntry("pending_count", 1L);
                    assertThat(((Timestamp) row.get("oldest_pending_at")).toInstant())
                            .isEqualTo(clock.instant().plusSeconds(4));
                });
    }

    @Test
    void deliveryRejectsAConsumerWhoseSubjectDoesNotMatchItsAuthorizedScope() {
        AuthorizedReadScope scope = new AuthorizedReadScope(SUBJECT, Set.of(REGION));

        assertThatThrownBy(() -> service().drain(
                CONSUMER + ":subject-mismatch", "instance-subject-mismatch", scope,
                FULL_SCOPE_SUBJECT, initialSequence, 20, ignored -> {}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must match");
        assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.business_event_delivery_checkpoint
                WHERE consumer_id=:consumer
                """).param("consumer", CONSUMER + ":subject-mismatch")
                .query(Long.class).single()).isZero();
    }

    @Test
    void retiredConnectionStopsContributingFalseBacklogAndReconnectCompensatesFromItsCursor() {
        String retiredConsumer = CONSUMER + ":retired-connection";
        String reconnectConsumer = CONSUMER + ":reconnected";
        BusinessEventDeliveryService service = service();
        AuthorizedReadScope scope = new AuthorizedReadScope(SUBJECT, Set.of(REGION));
        AtomicBoolean disconnectOnce = new AtomicBoolean(true);
        var first = service.drain(retiredConsumer, "instance-retiring", scope, SUBJECT,
                initialSequence, 20, event -> {
                    if (disconnectOnce.compareAndSet(true, false)) {
                        throw new IllegalStateException("connection closed before first event completed");
                    }
                });

        assertThat(service.retireConsumer(retiredConsumer, "instance-retiring",
                first.resumeSequence(), ConsumerRetirementReason.CLIENT_COMPLETED)).isTrue();
        assertThat(service.retireConsumer(retiredConsumer, "instance-retiring",
                first.resumeSequence(), ConsumerRetirementReason.CLIENT_COMPLETED)).isFalse();
        assertThat(service.backlog(retiredConsumer, scope, first.resumeSequence()))
                .satisfies(backlog -> {
                    assertThat(backlog.pendingCount()).isZero();
                    assertThat(backlog.retryScheduledCount()).isZero();
                    assertThat(backlog.inProgressCount()).isZero();
                    assertThat(backlog.oldestPendingAt()).isNull();
                });
        assertThat(jdbc.sql("""
                SELECT lifecycle_status,retirement_reason,resume_sequence
                FROM platform.business_event_delivery_checkpoint WHERE consumer_id=:consumer
                """).param("consumer", retiredConsumer).query().singleRow())
                .containsEntry("lifecycle_status", "RETIRED")
                .containsEntry("retirement_reason", "CLIENT_COMPLETED")
                .containsEntry("resume_sequence", first.resumeSequence());
        assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.business_event_delivery_backlog
                WHERE consumer_id=:consumer
                """).param("consumer", retiredConsumer).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.business_event_consumer_lifecycle_event
                WHERE consumer_id=:consumer AND lifecycle_status='RETIRED'
                """).param("consumer", retiredConsumer).query(Long.class).single()).isOne();

        ConcurrentHashMap<UUID, Integer> reconnected = new ConcurrentHashMap<>();
        var compensated = service.drain(reconnectConsumer, "instance-reconnected", scope, SUBJECT,
                first.resumeSequence(), 20, event -> reconnected.merge(event.id(), 1, Integer::sum));
        assertThat(compensated.failedCount()).isZero();
        assertThat(reconnected).containsEntry(FIRST, 1).containsEntry(POISON, 1).containsEntry(THIRD, 1);
    }

    @Test
    void staleConnectionIsAuditedAsExpiredAndExcludedFromOperationalBacklog() {
        String consumer = CONSUMER + ":expired-connection";
        BusinessEventDeliveryService service = service();
        AuthorizedReadScope scope = new AuthorizedReadScope(SUBJECT, Set.of(REGION));
        service.drain(consumer, "instance-crashed", scope, SUBJECT, initialSequence, 20,
                event -> { throw new IllegalStateException("crashed connection"); });

        jdbc.sql("""
                UPDATE platform.business_event_delivery_checkpoint
                SET lease_expires_at=clock_timestamp()-interval '1 second'
                WHERE consumer_id=:consumer
                """).param("consumer", consumer).update();

        assertThat(service.expireStaleConsumers()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.sql("""
                SELECT lifecycle_status,retirement_reason
                FROM platform.business_event_delivery_checkpoint WHERE consumer_id=:consumer
                """).param("consumer", consumer).query().singleRow())
                .containsEntry("lifecycle_status", "EXPIRED")
                .containsEntry("retirement_reason", "LEASE_EXPIRED");
        assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.business_event_delivery_backlog
                WHERE consumer_id=:consumer
                """).param("consumer", consumer).query(Long.class).single()).isZero();
        assertThat(service.backlog(consumer, scope, initialSequence).pendingCount()).isZero();
    }

    @Test
    void concurrentCloseAndLeaseExpiryAppendExactlyOneTerminalLifecycleEvent() throws Exception {
        String consumer = CONSUMER + ":close-expiry-race";
        BusinessEventDeliveryService service = service();
        AuthorizedReadScope scope = new AuthorizedReadScope(SUBJECT, Set.of(REGION));
        var drain = service.drain(consumer, "instance-race", scope, SUBJECT, initialSequence, 20,
                ignored -> {});
        jdbc.sql("""
                UPDATE platform.business_event_delivery_checkpoint
                SET lease_expires_at=clock_timestamp()-interval '1 second'
                WHERE consumer_id=:consumer
                """).param("consumer", consumer).update();

        try (var executor = Executors.newFixedThreadPool(2)) {
            var outcomes = executor.invokeAll(List.<Callable<Boolean>>of(
                    () -> service.retireConsumer(consumer, "instance-race", drain.resumeSequence(),
                            ConsumerRetirementReason.CLIENT_COMPLETED),
                    () -> service.expireStaleConsumers() > 0));
            assertThat(outcomes.get(0).get() || outcomes.get(1).get()).isTrue();
        }

        assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.business_event_consumer_lifecycle_event
                WHERE consumer_id=:consumer
                """).param("consumer", consumer).query(Long.class).single()).isOne();
        assertThat(jdbc.sql("""
                SELECT lifecycle_status<>'ACTIVE'
                FROM platform.business_event_delivery_checkpoint WHERE consumer_id=:consumer
                """).param("consumer", consumer).query(Boolean.class).single()).isTrue();
    }

    @Test
    void lifecycleAuditCannotBeForgedOrErasedByTheRuntimeRole() throws Exception {
        String consumer = CONSUMER + ":acl-probe";
        BusinessEventDeliveryService service = service();
        AuthorizedReadScope scope = new AuthorizedReadScope(SUBJECT, Set.of(REGION));
        service.drain(consumer, "instance-acl", scope, SUBJECT, initialSequence, 1, ignored -> {});

        assertThat(jdbc.sql("""
                SELECT NOT has_table_privilege('cofco_app',
                         'platform.business_event_consumer_lifecycle_event','INSERT,UPDATE,DELETE')
                   AND NOT has_table_privilege('cofco_app',
                         'platform.business_event_delivery_checkpoint','INSERT,UPDATE,DELETE')
                   AND has_column_privilege('cofco_app',
                         'platform.business_event_delivery_checkpoint','last_observed_sequence','UPDATE')
                   AND NOT has_column_privilege('cofco_app',
                         'platform.business_event_delivery_checkpoint','lifecycle_status','UPDATE')
                   AND NOT has_column_privilege('cofco_app',
                         'platform.business_event_delivery_checkpoint','authorization_subject_id','UPDATE')
                   AND NOT has_sequence_privilege('cofco_app',
                         'platform.business_event_consumer_lifecycle_event_lifecycle_event_id_seq','USAGE')
                   AND NOT has_function_privilege('cofco_app',
                         'platform.ensure_business_event_consumer(varchar,varchar,bigint)','EXECUTE')
                   AND has_function_privilege('cofco_app',
                         'platform.ensure_business_event_consumer(varchar,varchar,bigint,varchar)','EXECUTE')
                   AND has_function_privilege('cofco_app',
                         'platform.retire_business_event_consumer(varchar,varchar,bigint,varchar)','EXECUTE')
                   AND has_function_privilege('cofco_app',
                         'platform.expire_business_event_consumers()','EXECUTE')
                   AND NOT has_table_privilege('cofco_app',
                         'platform.business_event_delivery_backlog','SELECT')
                   AND has_table_privilege('qiqihar_event_operations_monitor',
                         'platform.business_event_delivery_backlog','SELECT')
                """).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("""
                SELECT NOT EXISTS (
                  SELECT 1 FROM pg_constraint
                  WHERE conrelid='platform.business_event_consumer_lifecycle_event'::regclass
                    AND contype='f' AND confdeltype='c')
                """).query(Boolean.class).single()).isTrue();

        assertThatThrownBy(() -> executeAsRuntime("""
                INSERT INTO platform.business_event_consumer_lifecycle_event(
                  consumer_id,instance_id,lifecycle_status,reason_code,resume_sequence,occurred_at)
                VALUES('def-100-shared-consumer:acl-probe','instance-acl','RETIRED',
                  'CLIENT_COMPLETED',0,clock_timestamp())
                """)).hasMessageContaining("permission denied");
        assertThatThrownBy(() -> executeAsRuntime("""
                DELETE FROM platform.business_event_delivery_checkpoint
                WHERE consumer_id='def-100-shared-consumer:acl-probe'
                """)).hasMessageContaining("permission denied");
        assertThatThrownBy(() -> executeAsRuntime("""
                UPDATE platform.business_event_delivery_checkpoint
                SET authorization_subject_id='def-103-full-scope-reader'
                WHERE consumer_id='def-100-shared-consumer:acl-probe'
                """)).hasMessageContaining("permission denied");
        assertThatThrownBy(() -> executeAsRuntime("""
                SELECT * FROM platform.business_event_delivery_backlog
                """)).hasMessageContaining("permission denied");
        assertThatThrownBy(() -> executeAsRuntime("""
                SELECT platform.ensure_business_event_consumer(
                  'def-100-shared-consumer:acl-probe','instance-acl',0)
                """)).hasMessageContaining("permission denied");
        executeAsRuntime("""
                SELECT platform.ensure_business_event_consumer(
                  'def-100-shared-consumer:acl-probe','instance-rebind',0,
                  'def-103-full-scope-reader')
                """);
        assertThat(jdbc.sql("""
                SELECT authorization_subject_id
                FROM platform.business_event_delivery_checkpoint
                WHERE consumer_id=:consumer
                """).param("consumer", consumer).query(String.class).single()).isEqualTo(SUBJECT);
        assertThatThrownBy(() -> executeAsRuntime("""
                SELECT platform.retire_business_event_consumer(
                  'def-100-shared-consumer:acl-probe','instance-acl',0,'FORGED_REASON')
                """)).hasMessageContaining("consumer retirement reason is invalid");
    }

    @Test
    void staleConsumerExpiryHasAnAutonomousScheduledEntry() throws Exception {
        assertThat(AnnotatedElementUtils.hasAnnotation(
                BusinessEventDeliveryService.class.getMethod("expireStaleConsumers"), Scheduled.class))
                .isTrue();
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

    private void executeAsRuntime(String sql) {
        try (Connection connection = dataSource.getConnection();
                Statement authorization = connection.createStatement()) {
            authorization.execute("SET SESSION AUTHORIZATION cofco_app");
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            } finally {
                authorization.execute("RESET SESSION AUTHORIZATION");
            }
        } catch (Exception exception) {
            throw new IllegalStateException(exception.getMessage(), exception);
        }
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
        insertEvent(id, aggregateId, occurredAt, REGION);
    }

    private void insertEvent(UUID id, String aggregateId, Instant occurredAt, String region) {
        jdbc.sql("""
                INSERT INTO platform.business_event_outbox(
                  event_id,aggregate_type,aggregate_id,action_code,actor_subject_id,
                  work_unit_code,region_codes,product_code,occurred_at,detail)
                VALUES(:id,'DELIVERY_PROBE',:aggregateId,'DELIVERY_PROBE_CREATED','production-tester',
                  'DEF_100_TEST',ARRAY[:region],'CORN',:occurredAt,
                  jsonb_build_object('regionCode',:region,'productCode','CORN','surveyYear',2026))
                """).param("id", id).param("aggregateId", aggregateId).param("region", region)
                .param("occurredAt", Timestamp.from(occurredAt)).update();
    }

    private void ensureAuthorizationFixture() {
        jdbc.sql("""
                INSERT INTO platform.work_unit(code,name,sort_order)
                SELECT 'DEF_100_TEST','事件积压授权测试单位',COALESCE(max(sort_order),0)+1
                FROM platform.work_unit
                ON CONFLICT(code) DO NOTHING
                """).update();
        jdbc.sql("""
                INSERT INTO platform.work_unit_region_scope(work_unit_code,region_code)
                VALUES('DEF_100_TEST',:region),('DEF_100_TEST',:hiddenRegion)
                ON CONFLICT DO NOTHING
                """).param("region", REGION).param("hiddenRegion", HIDDEN_REGION).update();
        jdbc.sql("""
                INSERT INTO platform.security_user(subject_id,display_name,work_unit_code)
                VALUES(:subject,'事件积压授权测试员工','DEF_100_TEST'),
                      (:fullSubject,'事件积压全范围测试员工','DEF_100_TEST'),
                      (:emptySubject,'事件积压空范围测试员工','DEF_100_TEST')
                ON CONFLICT(subject_id) DO NOTHING
                """).param("subject", SUBJECT).param("fullSubject", FULL_SCOPE_SUBJECT)
                .param("emptySubject", EMPTY_SCOPE_SUBJECT).update();
        jdbc.sql("""
                INSERT INTO platform.security_user_role(subject_id,role_code,valid_from)
                VALUES(:subject,'SYSTEM_ADMIN','-infinity'),
                      (:fullSubject,'SYSTEM_ADMIN','-infinity'),
                      (:emptySubject,'SYSTEM_ADMIN','-infinity')
                ON CONFLICT DO NOTHING
                """).param("subject", SUBJECT).param("fullSubject", FULL_SCOPE_SUBJECT)
                .param("emptySubject", EMPTY_SCOPE_SUBJECT).update();
        jdbc.sql("""
                INSERT INTO platform.security_user_region_scope(subject_id,region_code,valid_from)
                VALUES(:subject,:region,'-infinity'),
                      (:fullSubject,:region,'-infinity'),
                      (:fullSubject,:hiddenRegion,'-infinity')
                ON CONFLICT DO NOTHING
                """).param("subject", SUBJECT).param("fullSubject", FULL_SCOPE_SUBJECT)
                .param("region", REGION).param("hiddenRegion", HIDDEN_REGION).update();
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
        jdbc.sql("DELETE FROM platform.business_event_consumer_lifecycle_event WHERE consumer_id LIKE :consumer")
                .param("consumer", CONSUMER + "%").update();
        jdbc.sql("DELETE FROM platform.business_event_delivery_checkpoint WHERE consumer_id LIKE :consumer")
                .param("consumer", CONSUMER + "%").update();
        jdbc.sql("DELETE FROM platform.business_event_outbox WHERE event_id IN (:ids)")
                .param("ids", List.of(FIRST, POISON, THIRD, HIDDEN, HIDDEN_BACKLOG, VISIBLE_BACKLOG))
                .update();
        List<String> subjects = List.of(SUBJECT, FULL_SCOPE_SUBJECT, EMPTY_SCOPE_SUBJECT);
        jdbc.sql("DELETE FROM platform.security_user_region_scope WHERE subject_id IN (:subjects)")
                .param("subjects", subjects).update();
        jdbc.sql("DELETE FROM platform.security_user_role WHERE subject_id IN (:subjects)")
                .param("subjects", subjects).update();
        jdbc.sql("DELETE FROM platform.security_user WHERE subject_id IN (:subjects)")
                .param("subjects", subjects).update();
        jdbc.sql("DELETE FROM platform.work_unit_region_scope WHERE work_unit_code='DEF_100_TEST'")
                .update();
        jdbc.sql("DELETE FROM platform.work_unit WHERE code='DEF_100_TEST'").update();
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
