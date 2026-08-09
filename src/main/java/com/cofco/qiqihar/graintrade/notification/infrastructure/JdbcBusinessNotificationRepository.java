package com.cofco.qiqihar.graintrade.notification.infrastructure;

import com.cofco.qiqihar.graintrade.notification.application.BusinessNotification;
import com.cofco.qiqihar.graintrade.notification.application.BusinessNotificationRepository;
import com.cofco.qiqihar.graintrade.shared.security.application.AuthorizedReadScope;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcBusinessNotificationRepository implements BusinessNotificationRepository {
    private static final String SELECT = """
            SELECT event.event_id,event.event_sequence,event.aggregate_type,event.aggregate_id,
                   event.action_code,event.product_code,event.region_codes,event.occurred_at,
                   receipt.event_id IS NOT NULL AS is_read
            FROM platform.business_event_outbox event
            LEFT JOIN platform.notification_read_receipt receipt
              ON receipt.event_id=event.event_id AND receipt.subject_id=:subjectId
            """;
    private final JdbcClient jdbc;

    public JdbcBusinessNotificationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<BusinessNotification> findVisible(
            AuthorizedReadScope scope, String subjectId, int limit) {
        if (hasNoAuthorizedRegions(scope)) {
            return List.of();
        }
        String sql = SELECT + scopeClause(scope) + " ORDER BY event.event_sequence DESC LIMIT :limit";
        JdbcClient.StatementSpec statement = jdbc.sql(sql).param("subjectId", subjectId).param("limit", limit);
        statement = bindRegions(statement, scope.regionCodes(), scope.isUnrestricted());
        return statement.query(this::notification).list();
    }

    @Override
    public List<BusinessNotification> findVisibleAfter(
            AuthorizedReadScope scope, String subjectId, long afterSequence, int limit) {
        if (hasNoAuthorizedRegions(scope)) {
            return List.of();
        }
        String sql = SELECT + scopeClause(scope)
                + " AND event.event_sequence > :afterSequence"
                + " ORDER BY event.event_sequence ASC LIMIT :limit";
        JdbcClient.StatementSpec statement = jdbc.sql(sql)
                .param("subjectId", subjectId)
                .param("afterSequence", afterSequence)
                .param("limit", limit);
        statement = bindRegions(statement, scope.regionCodes(), scope.isUnrestricted());
        return statement.query(this::notification).list();
    }

    @Override
    public long countUnread(AuthorizedReadScope scope, String subjectId) {
        if (hasNoAuthorizedRegions(scope)) {
            return 0;
        }
        String sql = """
                SELECT count(*)
                FROM platform.business_event_outbox event
                LEFT JOIN platform.notification_read_receipt receipt
                  ON receipt.event_id=event.event_id AND receipt.subject_id=:subjectId
                """ + scopeClause(scope) + " AND receipt.event_id IS NULL";
        JdbcClient.StatementSpec statement = jdbc.sql(sql).param("subjectId", subjectId);
        statement = bindRegions(statement, scope.regionCodes(), scope.isUnrestricted());
        return statement.query(Long.class).single();
    }

    @Override
    public Optional<BusinessNotification> findVisibleById(
            UUID eventId, AuthorizedReadScope scope, String subjectId) {
        if (hasNoAuthorizedRegions(scope)) {
            return Optional.empty();
        }
        String sql = SELECT + scopeClause(scope) + " AND event.event_id=CAST(:eventId AS uuid)";
        JdbcClient.StatementSpec statement = jdbc.sql(sql)
                .param("subjectId", subjectId).param("eventId", eventId.toString());
        statement = bindRegions(statement, scope.regionCodes(), scope.isUnrestricted());
        return statement.query(this::notification).optional();
    }

    @Override
    public void markRead(UUID eventId, String subjectId, Instant readAt) {
        jdbc.sql("""
                INSERT INTO platform.notification_read_receipt(event_id,subject_id,read_at)
                VALUES(CAST(:eventId AS uuid),:subjectId,:readAt)
                ON CONFLICT(event_id,subject_id) DO UPDATE SET read_at=EXCLUDED.read_at
                """).param("eventId", eventId.toString()).param("subjectId", subjectId)
                .param("readAt", Timestamp.from(readAt)).update();
    }

    private static String scopeClause(AuthorizedReadScope scope) {
        return scope.isUnrestricted()
                ? " WHERE true"
                : " WHERE EXISTS (SELECT 1 FROM unnest(event.region_codes) region_code"
                        + " WHERE region_code IN (:authorizedRegions))";
    }

    private static boolean hasNoAuthorizedRegions(AuthorizedReadScope scope) {
        return !scope.isUnrestricted() && scope.regionCodes().isEmpty();
    }

    private static JdbcClient.StatementSpec bindRegions(
            JdbcClient.StatementSpec statement, Set<String> regionCodes, boolean unrestricted) {
        return unrestricted ? statement : statement.param("authorizedRegions", regionCodes);
    }

    private BusinessNotification notification(ResultSet row, int ignored) throws SQLException {
        Array regionArray = row.getArray("region_codes");
        String[] regions = regionArray == null ? new String[0] : (String[]) regionArray.getArray();
        return new BusinessNotification(
                row.getObject("event_id", UUID.class), row.getLong("event_sequence"),
                row.getString("aggregate_type"), row.getString("aggregate_id"),
                row.getString("action_code"), row.getString("product_code"),
                Arrays.asList(regions), row.getTimestamp("occurred_at").toInstant(),
                row.getBoolean("is_read"));
    }
}
