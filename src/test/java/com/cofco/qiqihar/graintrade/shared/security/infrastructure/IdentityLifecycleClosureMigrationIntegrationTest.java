package com.cofco.qiqihar.graintrade.shared.security.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(classes = GrainTradeApplication.class)
@UsesProtectedTestDatabase
class IdentityLifecycleClosureMigrationIntegrationTest {
    @Autowired DataSource dataSource;

    @Test
    void createsHashedSingleUseInvitationsDurableDeliveryAndSharedSessions() {
        JdbcClient jdbc = JdbcClient.create(dataSource);

        assertThat(regclass(jdbc, "platform.identity_invitation"))
                .contains("platform.identity_invitation");
        assertThat(regclass(jdbc, "platform.identity_delivery_outbox"))
                .contains("platform.identity_delivery_outbox");
        assertThat(regclass(jdbc, "platform.oidc_session_registry"))
                .contains("platform.oidc_session_registry");
        assertThat(regclass(jdbc, "platform.http_session"))
                .contains("platform.http_session");
        assertThat(regclass(jdbc, "platform.http_session_attributes"))
                .contains("platform.http_session_attributes");

        assertThat(jdbc.sql("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema='platform' AND table_name='security_user'
                  AND column_name='session_version'
                """).query(Long.class).single()).isOne();
        assertThat(jdbc.sql("""
                SELECT string_agg(column_name,',' ORDER BY column_name)
                FROM information_schema.columns
                WHERE table_schema='platform' AND table_name='identity_invitation'
                  AND column_name IN (
                    'token_hash','expires_at','state','idempotency_key','request_fingerprint',
                    'activated_at','revoked_at')
                """).query(String.class).single()).isEqualTo(
                "activated_at,expires_at,idempotency_key,request_fingerprint,revoked_at,state,token_hash");
        assertThat(jdbc.sql("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema='platform' AND table_name='identity_invitation'
                  AND column_name IN ('token','activation_token','raw_token')
                """).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("""
                SELECT has_table_privilege(
                  'qiqihar_enterprise_runtime','platform.identity_invitation','SELECT')
                """).query(Boolean.class).single()).isFalse();
    }

    private static java.util.Optional<String> regclass(JdbcClient jdbc, String name) {
        return jdbc.sql("SELECT to_regclass(:name)::text")
                .param("name", name).query(String.class).optional();
    }
}
