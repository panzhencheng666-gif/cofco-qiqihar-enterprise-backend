package com.cofco.qiqihar.graintrade.shared.security.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.authentication.logout.OidcLogoutToken;
import org.springframework.security.oauth2.client.oidc.session.OidcSessionInformation;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes=GrainTradeApplication.class)
@UsesProtectedTestDatabase
class JdbcOidcSessionRegistryIntegrationTest {
    private static final String ISSUER="https://oidc-session-test.example/realms/enterprise";
    private static final String PROVIDER_SUBJECT="provider-employee-001";
    private static final String AUDIENCE="enterprise";
    @Autowired DataSource dataSource;
    @Autowired ObjectMapper json;
    private JdbcClient jdbc;
    private String subject;

    @BeforeEach
    void prepareBinding() {
        jdbc=JdbcClient.create(dataSource);
        subject="oidc-session-"+UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO platform.security_user(subject_id,display_name,work_unit_code)
                VALUES(:subject,'OIDC 共享会话测试员工','TEST')
                """).param("subject",subject).update();
        jdbc.sql("""
                INSERT INTO platform.identity_provider_binding(
                    binding_id,provider_code,issuer_uri,provider_subject,security_subject_id,approved_by)
                VALUES(:id,'KEYCLOAK',:issuer,:providerSubject,:subject,'production-tester')
                """).param("id",UUID.randomUUID()).param("issuer",ISSUER)
                .param("providerSubject",PROVIDER_SUBJECT).param("subject",subject).update();
    }

    @AfterEach
    void cleanup() {
        if(jdbc==null)return;
        jdbc.sql("DELETE FROM platform.oidc_session_registry WHERE security_subject_id=:subject")
                .param("subject",subject).update();
        jdbc.sql("DELETE FROM platform.identity_provider_binding WHERE security_subject_id=:subject")
                .param("subject",subject).update();
        jdbc.sql("DELETE FROM platform.security_user WHERE subject_id=:subject")
                .param("subject",subject).update();
    }

    @Test
    void newestLoginIsPersistedAndRevokesTheOlderSharedHttpSession() {
        JdbcOidcSessionRegistry registry=new JdbcOidcSessionRegistry(jdbc,json,1);
        String first=UUID.randomUUID().toString();
        String second=UUID.randomUUID().toString();
        insertHttpSession(first);
        registry.saveSessionInformation(session(first,"provider-session-1"));
        insertHttpSession(second);

        registry.saveSessionInformation(session(second,"provider-session-2"));

        assertThat(jdbc.sql("SELECT count(*) FROM platform.http_session WHERE session_id=:id")
                .param("id",first).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("""
                SELECT revocation_reason FROM platform.oidc_session_registry WHERE session_id=:id
                """).param("id",first).query(String.class).single())
                .isEqualTo("CONCURRENT_SESSION_LIMIT");
        assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.oidc_session_registry
                WHERE session_id=:id AND revoked_at IS NULL AND security_subject_id=:subject
                """).param("id",second).param("subject",subject).query(Long.class).single()).isOne();
    }

    @Test
    void backChannelLogoutMatchesExactIssuerAudienceAndProviderSession() {
        JdbcOidcSessionRegistry registry=new JdbcOidcSessionRegistry(jdbc,json,2);
        String target=UUID.randomUUID().toString();
        String sibling=UUID.randomUUID().toString();
        registry.saveSessionInformation(session(target,"provider-session-target"));
        registry.saveSessionInformation(session(sibling,"provider-session-sibling"));
        OidcLogoutToken wrongAudience=OidcLogoutToken.withTokenValue("signed-token")
                .issuer(ISSUER).audience(List.of("another-client"))
                .sessionId("provider-session-target").issuedAt(Instant.now())
                .jti(UUID.randomUUID().toString()).events(logoutEvents()).build();
        OidcLogoutToken exact=OidcLogoutToken.withTokenValue("signed-token")
                .issuer(ISSUER).audience(List.of(AUDIENCE))
                .sessionId("provider-session-target").issuedAt(Instant.now())
                .jti(UUID.randomUUID().toString()).events(logoutEvents()).build();

        assertThat(registry.removeSessionInformation(wrongAudience)).isEmpty();
        assertThat(registry.removeSessionInformation(exact))
                .extracting(OidcSessionInformation::getSessionId).containsExactly(target);
        assertThat(jdbc.sql("SELECT count(*) FROM platform.oidc_session_registry WHERE session_id=:id")
                .param("id",target).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM platform.oidc_session_registry WHERE session_id=:id")
                .param("id",sibling).query(Long.class).single()).isOne();
    }

    @Test
    void unboundOidcLoginRemainsAvailableOnlyForInvitationActivation() {
        jdbc.sql("DELETE FROM platform.identity_provider_binding WHERE security_subject_id=:subject")
                .param("subject",subject).update();
        JdbcOidcSessionRegistry registry=new JdbcOidcSessionRegistry(jdbc,json,1);
        String sessionId=UUID.randomUUID().toString();

        registry.saveSessionInformation(session(sessionId,"provider-session-unbound"));

        assertThat(jdbc.sql("SELECT count(*) FROM platform.oidc_session_registry WHERE session_id=:id")
                .param("id",sessionId).query(Long.class).single()).isZero();
    }

    private OidcSessionInformation session(String sessionId,String providerSessionId) {
        Instant now=Instant.now();
        OidcIdToken token=OidcIdToken.withTokenValue("id-token")
                .issuer(ISSUER).subject(PROVIDER_SUBJECT).audience(List.of(AUDIENCE))
                .issuedAt(now).expiresAt(now.plusSeconds(1800)).claim("sid",providerSessionId).build();
        DefaultOidcUser user=new DefaultOidcUser(List.of(new SimpleGrantedAuthority("OIDC_USER")),token);
        return new OidcSessionInformation(sessionId,Map.of("X-CSRF-TOKEN","csrf-token"),user);
    }

    private void insertHttpSession(String sessionId) {
        long now=System.currentTimeMillis();
        jdbc.sql("""
                INSERT INTO platform.http_session(primary_id,session_id,creation_time,last_access_time,
                    max_inactive_interval,expiry_time,principal_name)
                VALUES(:id,:id,:now,:now,1800,:expiry,:subject)
                """).param("id",sessionId).param("now",now).param("expiry",now+1_800_000)
                .param("subject",subject).update();
    }

    private static Map<String,Object> logoutEvents() {
        return Map.of("http://schemas.openid.net/event/backchannel-logout",Map.of());
    }
}
