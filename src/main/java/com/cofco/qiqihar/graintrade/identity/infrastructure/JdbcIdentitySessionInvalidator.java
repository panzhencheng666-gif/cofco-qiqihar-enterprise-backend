package com.cofco.qiqihar.graintrade.identity.infrastructure;

import com.cofco.qiqihar.graintrade.identity.application.IdentitySessionInvalidator;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public final class JdbcIdentitySessionInvalidator implements IdentitySessionInvalidator {
    private final JdbcClient jdbc;
    private final Clock clock;

    public JdbcIdentitySessionInvalidator(JdbcClient jdbc,Clock clock) {
        this.jdbc=jdbc;this.clock=clock;
    }

    @Override
    public void invalidate(String subjectId,String reason) {
        jdbc.sql("""
                UPDATE platform.security_user
                SET session_version=session_version+1
                WHERE subject_id=:subject
                """).param("subject",subjectId).update();
        List<String> sessionIds=jdbc.sql("""
                SELECT session_id FROM platform.oidc_session_registry
                WHERE security_subject_id=:subject AND revoked_at IS NULL
                FOR UPDATE
                """).param("subject",subjectId).query(String.class).list();
        if(sessionIds.isEmpty())return;
        jdbc.sql("""
                UPDATE platform.oidc_session_registry
                SET revoked_at=:now,revocation_reason=:reason
                WHERE session_id IN (:ids) AND revoked_at IS NULL
                """).param("now",Timestamp.from(clock.instant())).param("reason",reason)
                .param("ids",sessionIds).update();
        jdbc.sql("DELETE FROM platform.http_session WHERE session_id IN (:ids)")
                .param("ids",sessionIds).update();
    }
}
