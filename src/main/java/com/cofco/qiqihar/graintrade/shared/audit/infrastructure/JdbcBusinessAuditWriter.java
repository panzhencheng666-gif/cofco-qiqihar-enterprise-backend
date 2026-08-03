package com.cofco.qiqihar.graintrade.shared.audit.infrastructure;

import com.cofco.qiqihar.graintrade.shared.audit.application.BusinessAuditWriter;
import com.cofco.qiqihar.graintrade.shared.audit.domain.BusinessAuditEvent;
import java.sql.Timestamp;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcBusinessAuditWriter implements BusinessAuditWriter {
    private final JdbcClient jdbc;

    public JdbcBusinessAuditWriter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void append(BusinessAuditEvent event) {
        jdbc.sql("""
                INSERT INTO platform.business_audit_event(
                    event_id, aggregate_type, aggregate_id, action_code, actor_subject_id,
                    work_unit_code, occurred_at, detail)
                VALUES (CAST(:id AS uuid), :aggregateType, :aggregateId, :actionCode, :actor,
                    :workUnit, :occurredAt, CAST(:detail AS jsonb))
                """).param("id", event.id().toString()).param("aggregateType", event.aggregateType())
                .param("aggregateId", event.aggregateId()).param("actionCode", event.actionCode())
                .param("actor", event.actorSubjectId()).param("workUnit", event.workUnitCode())
                .param("occurredAt", Timestamp.from(event.occurredAt())).param("detail", event.detailJson()).update();
    }
}
