package com.cofco.qiqihar.graintrade.shared.security.infrastructure;

import com.cofco.qiqihar.graintrade.shared.security.application.SecuritySessionAuditRecorder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.sql.Timestamp;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcSecuritySessionAuditRecorder implements SecuritySessionAuditRecorder {
    private final JdbcClient jdbc;
    private final Clock clock;

    public JdbcSecuritySessionAuditRecorder(JdbcClient jdbc,Clock clock) {
        this.jdbc=jdbc;
        this.clock=clock;
    }

    @Override
    public void record(String subjectId,String sessionId,String actionCode,String detailJson) {
        jdbc.sql("""
                INSERT INTO platform.security_session_audit_event(
                    event_id,subject_id,session_hash,action_code,occurred_at,detail)
                VALUES(CAST(:id AS uuid),:subjectId,:sessionHash,:actionCode,:occurredAt,CAST(:detail AS jsonb))
                ON CONFLICT(session_hash,action_code) DO NOTHING
                """).param("id",UUID.randomUUID().toString()).param("subjectId",subjectId)
                .param("sessionHash",hash(sessionId)).param("actionCode",actionCode)
                .param("occurredAt",Timestamp.from(clock.instant())).param("detail",detailJson).update();
    }

    private static String hash(String sessionId) {
        if(sessionId==null||sessionId.isBlank())return null;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(sessionId.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to hash security session identifier",exception);
        }
    }
}
