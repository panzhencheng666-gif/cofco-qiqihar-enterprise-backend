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
        jdbc.sql("""
                WITH event_detail AS (SELECT CAST(:detail AS jsonb) AS value),
                event_regions AS (
                    SELECT CASE
                        WHEN jsonb_typeof(value->'regionCodes') = 'array'
                            THEN ARRAY(SELECT jsonb_array_elements_text(value->'regionCodes'))
                        WHEN NULLIF(value->>'regionCode','') IS NOT NULL
                            THEN ARRAY[value->>'regionCode']
                        ELSE ARRAY[]::text[]
                    END AS codes,
                    NULLIF(value->>'productCode','') AS product_code,
                    value AS detail
                    FROM event_detail
                )
                INSERT INTO platform.business_event_outbox(
                    event_id,aggregate_type,aggregate_id,action_code,actor_subject_id,
                    work_unit_code,region_codes,product_code,occurred_at,detail)
                SELECT CAST(:id AS uuid),:aggregateType,:aggregateId,:actionCode,:actor,
                       :workUnit,codes,product_code,:occurredAt,detail
                FROM event_regions
                WHERE cardinality(codes) > 0
                """).param("detail", event.detailJson()).param("id", event.id().toString())
                .param("aggregateType", event.aggregateType()).param("aggregateId", event.aggregateId())
                .param("actionCode", event.actionCode()).param("actor", event.actorSubjectId())
                .param("workUnit", event.workUnitCode()).param("occurredAt", Timestamp.from(event.occurredAt()))
                .update();
    }
}
