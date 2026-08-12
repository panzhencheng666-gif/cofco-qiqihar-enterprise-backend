package com.cofco.qiqihar.graintrade.shared.security.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.shared.security.application.SecuritySessionAuditRecorder;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(classes = GrainTradeApplication.class)
@UsesProtectedTestDatabase
class JdbcSecuritySessionAuditRecorderIntegrationTest {
    @Autowired SecuritySessionAuditRecorder recorder;
    @Autowired DataSource dataSource;

    @Test
    void hashesSessionIdentifiersDeduplicatesActionsAndRejectsMutation() {
        String rawSessionId = "security-session-audit-test-id";
        recorder.record("audit-subject", rawSessionId, "OIDC_BACK_CHANNEL_LOGOUT", "{\"mfa\":true}");
        recorder.record("audit-subject", rawSessionId, "OIDC_BACK_CHANNEL_LOGOUT", "{\"mfa\":true}");

        JdbcClient jdbc = JdbcClient.create(dataSource);
        var rows = jdbc.sql("""
                SELECT session_hash, detail::text
                FROM platform.security_session_audit_event
                WHERE subject_id='audit-subject' AND action_code='OIDC_BACK_CHANNEL_LOGOUT'
                """).query((resultSet, rowNumber) -> java.util.Map.of(
                        "hash", resultSet.getString("session_hash"),
                        "detail", resultSet.getString("detail"))).list();

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().get("hash"))
                .hasSize(64)
                .doesNotContain(rawSessionId);
        assertThat(rows.getFirst().get("detail")).contains("mfa");
        assertThatThrownBy(() -> jdbc.sql("""
                UPDATE platform.security_session_audit_event
                SET detail='{}'::jsonb
                WHERE subject_id='audit-subject' AND action_code='OIDC_BACK_CHANNEL_LOGOUT'
                """).update()).hasMessageContaining("security session audit events are immutable");
        assertThatThrownBy(() -> jdbc.sql("""
                DELETE FROM platform.security_session_audit_event
                WHERE subject_id='audit-subject' AND action_code='OIDC_BACK_CHANNEL_LOGOUT'
                """).update()).hasMessageContaining("security session audit events are immutable");
    }
}
