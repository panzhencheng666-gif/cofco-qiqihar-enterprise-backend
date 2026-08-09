package com.cofco.qiqihar.graintrade.shared.audit.infrastructure;

import com.cofco.qiqihar.graintrade.shared.audit.application.BusinessAuditActorReader;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcBusinessAuditActorReader implements BusinessAuditActorReader {
    private final JdbcClient jdbc;

    public JdbcBusinessAuditActorReader(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<String> latestActor(String aggregateType, String aggregateId, String actionCode) {
        return jdbc.sql("""
                SELECT actor_subject_id
                FROM platform.business_audit_event
                WHERE aggregate_type = :aggregateType
                  AND aggregate_id = :aggregateId
                  AND action_code = :actionCode
                ORDER BY occurred_at DESC, event_id DESC
                LIMIT 1
                """).param("aggregateType", aggregateType).param("aggregateId", aggregateId)
                .param("actionCode", actionCode).query(String.class).optional();
    }
}
