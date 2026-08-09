package com.cofco.qiqihar.graintrade.shared.audit.infrastructure;

import com.cofco.qiqihar.graintrade.shared.application.PagedResult;
import com.cofco.qiqihar.graintrade.shared.audit.application.BusinessAuditReader;
import com.cofco.qiqihar.graintrade.shared.audit.domain.BusinessAuditView;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcBusinessAuditReader implements BusinessAuditReader {
    private final JdbcClient jdbc;

    public JdbcBusinessAuditReader(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public PagedResult<BusinessAuditView> find(
            String workUnitCode,
            String aggregateType,
            String actorSubjectId,
            Instant occurredFrom,
            Instant occurredTo,
            int pageNumber,
            int pageSize) {
        String where = where(aggregateType, actorSubjectId, occurredFrom, occurredTo);
        long total = bind(jdbc.sql("SELECT count(*) FROM platform.business_audit_event e " + where),
                workUnitCode, aggregateType, actorSubjectId, occurredFrom, occurredTo)
                .query(Long.class).single();
        List<BusinessAuditView> items = bind(jdbc.sql("""
                SELECT e.event_id,e.aggregate_type,e.aggregate_id,e.action_code,e.actor_subject_id,
                       COALESCE(NULLIF(u.display_name,''),e.actor_subject_id) AS actor_display_name,
                       e.work_unit_code,e.occurred_at,e.detail::text AS detail_json
                FROM platform.business_audit_event e
                LEFT JOIN platform.security_user u ON u.subject_id=e.actor_subject_id
                """ + where + " ORDER BY e.occurred_at DESC,e.event_id DESC LIMIT :limit OFFSET :offset"),
                workUnitCode, aggregateType, actorSubjectId, occurredFrom, occurredTo)
                .param("limit", pageSize)
                .param("offset", Math.multiplyExact((long) pageNumber, pageSize))
                .query((rs, rowNumber) -> new BusinessAuditView(
                        UUID.fromString(rs.getString("event_id")),
                        rs.getString("aggregate_type"),
                        rs.getString("aggregate_id"),
                        rs.getString("action_code"),
                        rs.getString("actor_subject_id"),
                        rs.getString("actor_display_name"),
                        rs.getString("work_unit_code"),
                        rs.getTimestamp("occurred_at").toInstant(),
                        rs.getString("detail_json")))
                .list();
        return new PagedResult<>(items, pageNumber, pageSize, total);
    }

    private static String where(
            String aggregateType,
            String actorSubjectId,
            Instant occurredFrom,
            Instant occurredTo) {
        StringBuilder sql = new StringBuilder(" WHERE e.work_unit_code=:workUnit");
        if (aggregateType != null) sql.append(" AND e.aggregate_type=:aggregateType");
        if (actorSubjectId != null) sql.append(" AND e.actor_subject_id=:actor");
        if (occurredFrom != null) sql.append(" AND e.occurred_at>=:occurredFrom");
        if (occurredTo != null) sql.append(" AND e.occurred_at<=:occurredTo");
        return sql.toString();
    }

    private static JdbcClient.StatementSpec bind(
            JdbcClient.StatementSpec statement,
            String workUnitCode,
            String aggregateType,
            String actorSubjectId,
            Instant occurredFrom,
            Instant occurredTo) {
        statement = statement.param("workUnit", workUnitCode);
        if (aggregateType != null) statement = statement.param("aggregateType", aggregateType);
        if (actorSubjectId != null) statement = statement.param("actor", actorSubjectId);
        if (occurredFrom != null) statement = statement.param("occurredFrom", Timestamp.from(occurredFrom));
        if (occurredTo != null) statement = statement.param("occurredTo", Timestamp.from(occurredTo));
        return statement;
    }
}
