package com.cofco.qiqihar.graintrade.notification.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.shared.audit.application.BusinessAuditWriter;
import com.cofco.qiqihar.graintrade.shared.audit.domain.BusinessAuditEvent;
import com.cofco.qiqihar.graintrade.testsupport.ProtectedTestDatabaseConfiguration;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(classes = GrainTradeApplication.class)
@UsesProtectedTestDatabase
class BusinessEventDurabilityIntegrationTest {
    private static final UUID COMMITTED = UUID.fromString("98000000-0000-0000-0000-000000000001");
    private static final UUID ROLLED_BACK = UUID.fromString("98000000-0000-0000-0000-000000000002");

    @Autowired DataSource dataSource;
    @Autowired BusinessAuditWriter writer;
    @Autowired PlatformTransactionManager transactions;
    private JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        jdbc = JdbcClient.create(dataSource);
        cleanup();
        ProtectedTestDatabaseConfiguration.provisionSecurityTestSubjects(jdbc);
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    void auditAndDurableOutboxCommitOrRollbackAsOneBusinessTransaction() {
        TransactionTemplate transaction = new TransactionTemplate(transactions);
        transaction.executeWithoutResult(ignored -> writer.append(event(COMMITTED, "committed")));

        assertThat(count("platform.business_audit_event", COMMITTED)).isEqualTo(1);
        assertThat(count("platform.business_event_outbox", COMMITTED)).isEqualTo(1);

        assertThatThrownBy(() -> transaction.executeWithoutResult(ignored -> {
            writer.append(event(ROLLED_BACK, "rolled-back"));
            throw new IllegalStateException("force business rollback");
        })).hasMessageContaining("force business rollback");

        assertThat(count("platform.business_audit_event", ROLLED_BACK)).isZero();
        assertThat(count("platform.business_event_outbox", ROLLED_BACK)).isZero();
    }

    @Test
    void deliveryGovernanceKeepsPersistentConsumerStateAttemptHistoryAndBacklogProjection() {
        assertThat(jdbc.sql("SELECT to_regclass('platform.business_event_delivery_checkpoint')::text")
                .query(String.class).single()).isEqualTo("platform.business_event_delivery_checkpoint");
        assertThat(jdbc.sql("SELECT to_regclass('platform.business_event_delivery_state')::text")
                .query(String.class).single()).isEqualTo("platform.business_event_delivery_state");
        assertThat(jdbc.sql("SELECT to_regclass('platform.business_event_delivery_attempt')::text")
                .query(String.class).single()).isEqualTo("platform.business_event_delivery_attempt");
        assertThat(jdbc.sql("SELECT to_regclass('platform.business_event_poll_attempt')::text")
                .query(String.class).single()).isEqualTo("platform.business_event_poll_attempt");
        assertThat(jdbc.sql("SELECT to_regclass('platform.business_event_delivery_backlog')::text")
                .query(String.class).single()).isEqualTo("platform.business_event_delivery_backlog");
    }

    private static BusinessAuditEvent event(UUID id, String aggregateId) {
        return new BusinessAuditEvent(id, "DURABILITY_PROBE", aggregateId, "PROBE_COMMITTED",
                "production-tester", "TEST", Instant.parse("2026-08-12T10:00:00Z"),
                "{\"regionCode\":\"230208\",\"productCode\":\"CORN\",\"surveyYear\":2026}");
    }

    private long count(String relation, UUID id) {
        return jdbc.sql("SELECT count(*) FROM " + relation + " WHERE event_id=:id")
                .param("id", id).query(Long.class).single();
    }

    private void cleanup() {
        jdbc.sql("DELETE FROM platform.business_event_outbox WHERE event_id IN (:committed,:rolledBack)")
                .param("committed", COMMITTED).param("rolledBack", ROLLED_BACK).update();
    }
}
