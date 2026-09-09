package com.cofco.qiqihar.graintrade.identity.application;

import com.cofco.qiqihar.graintrade.shared.application.AccessDeniedException;
import com.cofco.qiqihar.graintrade.shared.security.application.SecurityPrincipalRepository;
import com.cofco.qiqihar.graintrade.shared.audit.application.BusinessAuditRecorder;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** One-time binding of an operator-controlled independent administrator after real OIDC login. */
@Service
public class FirstAdministratorService {
    private final JdbcClient jdbc;
    private final SecurityPrincipalRepository principals;
    private final BusinessAuditRecorder audit;
    private final FirstAdministratorClaim claim;
    private final String issuer;

    public FirstAdministratorService(JdbcClient jdbc,SecurityPrincipalRepository principals,
            BusinessAuditRecorder audit,
            @Value("${QIQIHAR_FIRST_ADMIN_SUBJECT:}") String subject,
            @Value("${QIQIHAR_FIRST_ADMIN_PROVIDER_SUBJECT:}") String expectedProviderSubject,
            @Value("${QIQIHAR_FIRST_ADMIN_TOKEN_SHA256:}") String hash,
            @Value("${QIQIHAR_FIRST_ADMIN_EXPIRES_AT:1970-01-01T00:00:00Z}") String expires,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:}") String issuer) {
        this.jdbc=jdbc;this.principals=principals;this.audit=audit;this.issuer=issuer;
        this.claim=new FirstAdministratorClaim(subject,expectedProviderSubject,hash,Instant.parse(expires));
    }

    @Transactional
    public void bind(String authenticatedIssuer,String providerSubject,String token) {
        if(!issuer.startsWith("https://")||!issuer.equals(authenticatedIssuer)
                ||providerSubject==null||providerSubject.isBlank()
                ||!claim.accepts(providerSubject,token,Instant.now()))throw denied();
        // Serialize against all invitation bindings, including concurrent first claims.
        jdbc.sql("LOCK TABLE platform.identity_provider_binding IN SHARE ROW EXCLUSIVE MODE").update();
        // Any historical binding closes this entrance permanently, even after revocation.
        long existing=jdbc.sql("""
                SELECT count(*) FROM platform.identity_provider_binding binding
                WHERE binding.security_subject_id=:subject
                   OR (binding.issuer_uri=:issuer AND binding.provider_subject=:providerSubject)
                   OR EXISTS (SELECT 1 FROM platform.security_user_role role
                       WHERE role.subject_id=binding.security_subject_id AND role.role_code='SYSTEM_ADMIN')
                """).param("subject",claim.subjectId()).param("issuer",issuer)
                .param("providerSubject",providerSubject).query(Long.class).single();
        if(existing!=0)throw denied();
        ensureIndependentAdmin();
        var principal=principals.findEnabled(claim.subjectId()).orElseThrow(FirstAdministratorService::denied);
        if(!principal.roleCodes().contains("SYSTEM_ADMIN"))throw denied();
        jdbc.sql("""
                INSERT INTO platform.identity_provider_binding(
                    binding_id,provider_code,issuer_uri,provider_subject,security_subject_id,approved_by)
                VALUES(:id,'ENTERPRISE_OIDC',:issuer,:providerSubject,:subject,:subject)
                """).param("id",UUID.randomUUID()).param("issuer",issuer)
                .param("providerSubject",providerSubject).param("subject",claim.subjectId()).update();
        audit.record(principal,"SECURITY_USER",claim.subjectId(),"FIRST_ADMINISTRATOR_BOUND",
                Instant.now(),"{\"approval\":\"operator_claim\"}");
    }
    private void ensureIndependentAdmin() {
        if(!"admin".equals(claim.subjectId()))throw denied();
        jdbc.sql("LOCK TABLE platform.work_unit, platform.security_user IN SHARE ROW EXCLUSIVE MODE").update();
        long existing=jdbc.sql("SELECT count(*) FROM platform.security_user WHERE subject_id='admin'")
                .query(Long.class).single();
        if(existing!=0)return; // Never overwrite or elevate an existing account.
        jdbc.sql("""
                INSERT INTO platform.work_unit(code,name,sort_order)
                SELECT 'PLATFORM_ADMIN','平台系统管理',COALESCE(MAX(sort_order),0)+1 FROM platform.work_unit
                ON CONFLICT(code) DO NOTHING
                """).update();
        jdbc.sql("""
                INSERT INTO platform.security_user(subject_id,display_name,work_unit_code)
                VALUES('admin','admin','PLATFORM_ADMIN')
                """).update();
        jdbc.sql("INSERT INTO platform.security_user_role(subject_id,role_code) VALUES('admin','SYSTEM_ADMIN')").update();
        jdbc.sql("""
                INSERT INTO platform.work_unit_region_scope(work_unit_code,region_code)
                SELECT 'PLATFORM_ADMIN',code FROM platform.region WHERE parent_code IS NULL
                ON CONFLICT DO NOTHING
                """).update();
        jdbc.sql("""
                INSERT INTO platform.security_user_region_scope(
                    subject_id,region_code,valid_from,granted_by,granted_at,last_reviewed_at,review_due_at)
                SELECT 'admin',region_code,now(),'admin',now(),now(),now()+interval '90 days'
                FROM platform.work_unit_region_scope WHERE work_unit_code='PLATFORM_ADMIN'
                """).update();
    }
    private static AccessDeniedException denied() {
        return new AccessDeniedException("FIRST_ADMINISTRATOR_CLAIM_DENIED","首次管理员开通凭证无效或入口已关闭");
    }
}
