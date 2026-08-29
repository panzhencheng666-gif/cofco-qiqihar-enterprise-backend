package com.cofco.qiqihar.graintrade.shared.security.infrastructure;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import java.sql.Array;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.authentication.logout.OidcLogoutToken;
import org.springframework.security.oauth2.client.oidc.session.OidcSessionInformation;
import org.springframework.security.oauth2.client.oidc.session.OidcSessionRegistry;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.transaction.annotation.Transactional;

/** Shared OIDC-to-browser-session mapping for multi-instance logout and concurrency. */
public final class JdbcOidcSessionRegistry implements OidcSessionRegistry {
    private static final TypeReference<Map<String,String>> STRING_MAP = new TypeReference<>() {};
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final int maximumSessions;

    public JdbcOidcSessionRegistry(JdbcClient jdbc,ObjectMapper json,int maximumSessions) {
        if(maximumSessions<1)throw new IllegalArgumentException("maximumSessions must be positive");
        this.jdbc=jdbc;this.json=json;this.maximumSessions=maximumSessions;
    }

    @Override
    @Transactional
    public void saveSessionInformation(OidcSessionInformation information) {
        var user=information.getPrincipal();
        if(user.getIssuer()==null||user.getSubject()==null||user.getAudience().isEmpty())
            throw new IllegalStateException("OIDC session identity is incomplete");
        Instant now=Instant.now();
        Instant expiresAt=user.getExpiresAt()==null?now.plusSeconds(1800):user.getExpiresAt();
        int inserted=jdbc.sql("""
                INSERT INTO platform.oidc_session_registry(
                    session_id,security_subject_id,issuer_uri,provider_subject,provider_session_id,
                    audience,logout_authorities,identity_version,created_at,last_seen_at,expires_at)
                SELECT :sessionId,binding.security_subject_id,:issuer,:providerSubject,:providerSession,
                       :audience,CAST(:authorities AS jsonb),security_user.session_version,:now,:now,:expiresAt
                FROM platform.identity_provider_binding binding
                JOIN platform.security_user security_user
                  ON security_user.subject_id=binding.security_subject_id
                WHERE binding.issuer_uri=:issuer AND binding.provider_subject=:providerSubject
                  AND binding.state='ACTIVE' AND CURRENT_TIMESTAMP>=binding.valid_from
                  AND (binding.valid_until IS NULL OR CURRENT_TIMESTAMP<binding.valid_until)
                ON CONFLICT(session_id) DO UPDATE SET
                    last_seen_at=EXCLUDED.last_seen_at,expires_at=EXCLUDED.expires_at,
                    logout_authorities=EXCLUDED.logout_authorities,revoked_at=NULL,revocation_reason=NULL
                """).param("sessionId",information.getSessionId())
                .param("issuer",user.getIssuer().toString()).param("providerSubject",user.getSubject())
                .param("providerSession",stringClaim(user.getClaims().get("sid")))
                .param("audience",user.getAudience().toArray(String[]::new))
                .param("authorities",write(information.getAuthorities()))
                .param("now",Timestamp.from(now)).param("expiresAt",Timestamp.from(expiresAt)).update();
        if(inserted==0)return;
        if(inserted!=1)throw new IllegalStateException("OIDC session registration was not unique");

        List<String> obsolete=jdbc.sql("""
                SELECT session_id FROM platform.oidc_session_registry
                WHERE security_subject_id=(SELECT security_subject_id FROM platform.oidc_session_registry
                                           WHERE session_id=:sessionId)
                  AND revoked_at IS NULL AND session_id<>:sessionId
                ORDER BY created_at DESC,session_id DESC
                OFFSET :retained
                """).param("sessionId",information.getSessionId())
                .param("retained",Math.max(0,maximumSessions-1)).query(String.class).list();
        revokeAndDeleteHttpSessions(obsolete,"CONCURRENT_SESSION_LIMIT",now);
    }

    @Override
    @Transactional
    public OidcSessionInformation removeSessionInformation(String sessionId) {
        OidcSessionInformation information=findBySessionId(sessionId);
        jdbc.sql("DELETE FROM platform.oidc_session_registry WHERE session_id=:sessionId")
                .param("sessionId",sessionId).update();
        return information;
    }

    @Override
    @Transactional
    public Iterable<OidcSessionInformation> removeSessionInformation(OidcLogoutToken token) {
        if(token.getIssuer()==null||token.getAudience().isEmpty())return List.of();
        String audience=token.getAudience().getFirst();
        String sid=token.getSessionId();
        List<OidcSessionInformation> matches=jdbc.sql("""
                SELECT session_id,issuer_uri,provider_subject,provider_session_id,audience,
                       logout_authorities::text,created_at,expires_at
                FROM platform.oidc_session_registry
                WHERE revoked_at IS NULL AND issuer_uri=:issuer AND :audience=ANY(audience)
                  AND ((:sid IS NOT NULL AND provider_session_id=:sid)
                    OR (:sid IS NULL AND provider_subject=:providerSubject))
                FOR UPDATE
                """).param("issuer",token.getIssuer().toString()).param("audience",audience)
                .param("sid",sid).param("providerSubject",token.getSubject())
                .query((row,index)->information(row.getString(1),row.getString(2),row.getString(3),
                        row.getString(4),strings(row.getArray(5)),row.getString(6),
                        row.getTimestamp(7).toInstant(),row.getTimestamp(8).toInstant())).list();
        if(!matches.isEmpty())jdbc.sql("DELETE FROM platform.oidc_session_registry WHERE session_id IN (:ids)")
                .param("ids",matches.stream().map(OidcSessionInformation::getSessionId).toList()).update();
        return matches;
    }

    @Transactional
    public void revokeSubject(String subjectId,String reason) {
        List<String> sessions=jdbc.sql("""
                SELECT session_id FROM platform.oidc_session_registry
                WHERE security_subject_id=:subject AND revoked_at IS NULL FOR UPDATE
                """).param("subject",subjectId).query(String.class).list();
        revokeAndDeleteHttpSessions(sessions,reason,Instant.now());
    }

    private OidcSessionInformation findBySessionId(String sessionId) {
        return jdbc.sql("""
                SELECT session_id,issuer_uri,provider_subject,provider_session_id,audience,
                       logout_authorities::text,created_at,expires_at
                FROM platform.oidc_session_registry WHERE session_id=:sessionId
                """).param("sessionId",sessionId)
                .query((row,index)->information(row.getString(1),row.getString(2),row.getString(3),
                        row.getString(4),strings(row.getArray(5)),row.getString(6),
                        row.getTimestamp(7).toInstant(),row.getTimestamp(8).toInstant())).optional().orElse(null);
    }

    private OidcSessionInformation information(String sessionId,String issuer,String subject,String sid,
            List<String> audience,String authorities,Instant issuedAt,Instant expiresAt) {
        OidcIdToken.Builder token=OidcIdToken.withTokenValue("persisted-session")
                .issuer(issuer).subject(subject).audience(audience).issuedAt(issuedAt).expiresAt(expiresAt);
        if(sid!=null)token.claim("sid",sid);
        var user=new DefaultOidcUser(List.of(new SimpleGrantedAuthority("OIDC_USER")),token.build());
        return new OidcSessionInformation(sessionId,read(authorities),user);
    }

    private void revokeAndDeleteHttpSessions(List<String> sessionIds,String reason,Instant now) {
        if(sessionIds.isEmpty())return;
        jdbc.sql("""
                UPDATE platform.oidc_session_registry
                SET revoked_at=:now,revocation_reason=:reason
                WHERE session_id IN (:ids) AND revoked_at IS NULL
                """).param("now",Timestamp.from(now)).param("reason",reason).param("ids",sessionIds).update();
        jdbc.sql("DELETE FROM platform.http_session WHERE session_id IN (:ids)")
                .param("ids",sessionIds).update();
    }

    private String write(Map<String,String> value) {
        try{return json.writeValueAsString(value);}
        catch(JacksonException failure){throw new IllegalStateException("OIDC logout metadata encoding failed",failure);}
    }

    private Map<String,String> read(String value) {
        try{return json.readValue(value,STRING_MAP);}
        catch(JacksonException failure){throw new IllegalStateException("OIDC logout metadata decoding failed",failure);}
    }

    private static String stringClaim(Object value){return value==null?null:value.toString();}
    private static List<String> strings(Array value) {
        if(value==null)return List.of();
        try{return Arrays.stream((Object[])value.getArray()).map(Object::toString).toList();}
        catch(java.sql.SQLException failure){throw new IllegalStateException("OIDC audience decoding failed",failure);}
    }
}
