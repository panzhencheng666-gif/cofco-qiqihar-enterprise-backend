package com.cofco.qiqihar.graintrade.identity.application;

import com.cofco.qiqihar.graintrade.shared.application.AccessDeniedException;
import com.cofco.qiqihar.graintrade.shared.application.ConflictException;
import com.cofco.qiqihar.graintrade.shared.audit.application.BusinessAuditRecorder;
import com.cofco.qiqihar.graintrade.shared.security.domain.SecurityPrincipal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Self-registration never imports roles or an existing business identity from user input. */
@Service
public class EmployeeRegistrationService {
    private final IdentityGovernanceRepository repository;
    private final IdentityGovernanceService governance;
    private final JdbcClient jdbc;
    private final BusinessAuditRecorder audit;
    private final boolean enabled;
    private final String issuer;

    public EmployeeRegistrationService(IdentityGovernanceRepository repository,
            IdentityGovernanceService governance,JdbcClient jdbc,BusinessAuditRecorder audit,
            @Value("${QIQIHAR_EMPLOYEE_REGISTRATION_ENABLED:false}") boolean enabled,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:}") String issuer) {
        this.repository=repository;this.governance=governance;this.jdbc=jdbc;
        this.audit=audit;this.enabled=enabled;this.issuer=issuer;
    }
    @Transactional(readOnly=true)
    public boolean alreadyRegistered(String issuerUri,String providerSubject) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM platform.identity_provider_binding WHERE issuer_uri=:issuer AND provider_subject=:subject)")
                .param("issuer",issuerUri).param("subject",providerSubject).query(Boolean.class).single();
    }
    @Transactional(readOnly=true)
    public AssignmentOptions options(String unit) {
        requireEnabled();
        AssignmentOptions all=governance.registrationOptions(unit);
        return new AssignmentOptions(all.workUnits(),all.roles().stream()
                .filter(role->role.code().equals("BUSINESS_OPERATOR")).toList(),
                all.positions(),all.regionCodes(),all.regions());
    }
    @Transactional
    public IdentityActivationResult register(String authenticatedIssuer,String providerSubject,
            String username,EmployeeAssignment requested) {
        requireEnabled();
        if(!issuer.startsWith("https://")||!issuer.equals(authenticatedIssuer)
                ||providerSubject==null||providerSubject.isBlank())throw denied();
        validateIdentity(username);
        if(requested==null)throw denied();
        validateRoles(requested.roleCodes());
        EmployeeAssignment assignment=new EmployeeAssignment(requested.displayName(),requested.workUnitCode(),
                "ACTIVE","ACTIVE",List.of("BUSINESS_OPERATOR"),requested.positionCodes(),requested.regionCodes());
        governance.validateRegistration(assignment);
        // Serialize concurrent username and identity binding claims.
        jdbc.sql("LOCK TABLE platform.identity_provider_binding IN SHARE ROW EXCLUSIVE MODE").update();
        jdbc.sql("LOCK TABLE platform.security_user IN SHARE ROW EXCLUSIVE MODE").update();
        var existing=jdbc.sql("SELECT security_subject_id FROM platform.identity_provider_binding WHERE issuer_uri=:issuer AND provider_subject=:subject")
                .param("issuer",issuer).param("subject",providerSubject).query(String.class).optional();
        if(existing.isPresent())throw new ConflictException("REGISTRATION_ALREADY_COMPLETE","账号已开通，请直接登录；重复提交不会修改资料或权限");
        long taken=jdbc.sql("SELECT count(*) FROM platform.security_user WHERE lower(subject_id)=lower(:username)")
                .param("username",username).query(Long.class).single();
        if(taken!=0)throw new ConflictException("REGISTRATION_USERNAME_EXISTS","该账号已存在，请通过原邀请激活或联系管理员");
        repository.create(username,assignment,username);
        jdbc.sql("UPDATE platform.security_user SET enabled=true,account_status='ACTIVE',activated_at=now() WHERE subject_id=:subject")
                .param("subject",username).update();
        jdbc.sql("""
                INSERT INTO platform.identity_provider_binding(binding_id,provider_code,issuer_uri,
                    provider_subject,security_subject_id,approved_by)
                VALUES(:id,'ENTERPRISE_OIDC',:issuer,:providerSubject,:subject,:subject)
                """).param("id",UUID.randomUUID()).param("issuer",issuer)
                .param("providerSubject",providerSubject).param("subject",username).update();
        var principal=new SecurityPrincipal(username,assignment.displayName(),assignment.workUnitCode(),
                Set.of(),Set.copyOf(assignment.regionCodes()));
        audit.record(principal,assignment.workUnitCode(),"SECURITY_USER",username,
                "SECURITY_USER_REGISTERED",Instant.now(),"{\"registration\":\"SELF_SERVICE\",\"role\":\"BUSINESS_OPERATOR\"}");
        return IdentityActivationResult.active(username);
    }
    static void validateIdentity(String username) {
        if(username==null||!username.matches("[A-Za-z0-9._:@-]{1,120}")
                ||username.equalsIgnoreCase("admin")||username.equalsIgnoreCase("identity-bootstrap"))throw denied();
    }
    static void validateRoles(List<String> roles) {
        if(!List.of("BUSINESS_OPERATOR").equals(roles))throw denied();
    }
    private void requireEnabled(){if(!enabled)throw denied();}
    private static AccessDeniedException denied(){return new AccessDeniedException(
            "REGISTRATION_NOT_ALLOWED","注册请求无效：仅允许创建普通员工账号");}
}
