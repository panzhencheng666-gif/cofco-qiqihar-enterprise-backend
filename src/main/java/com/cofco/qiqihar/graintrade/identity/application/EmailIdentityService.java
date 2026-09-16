package com.cofco.qiqihar.graintrade.identity.application;

import com.cofco.qiqihar.graintrade.shared.application.AccessDeniedException;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EmailIdentityService {
    public record Login(String subject,long sessionVersion) {}
    private final JdbcClient jdbc;
    private final EmployeeRegistrationService registration;
    public EmailIdentityService(JdbcClient jdbc,EmployeeRegistrationService registration){this.jdbc=jdbc;this.registration=registration;}

    @Transactional
    public IdentityActivationResult register(String issuer,String providerSubject,String username,
            EmployeeAssignment assignment,String verifiedAddress) {
        var result=registration.register(issuer,providerSubject,username,assignment);
        bind(username,verifiedAddress,true);
        return result;
    }

    @Transactional(readOnly=true)
    public void requireAvailable(String address) {
        String email=EmailChallengeService.normalize(address);
        if(jdbc.sql("SELECT EXISTS(SELECT 1 FROM platform.email_identity WHERE email_normalized=:email)")
                .param("email",email).query(Boolean.class).single())
            throw conflict("EMAIL_ALREADY_BOUND","该邮箱已绑定账号，请直接使用邮箱验证码登录");
    }

    @Transactional
    public void bind(String subject,String address,boolean verified) {
        String email=EmailChallengeService.normalize(address);
        jdbc.sql("SELECT pg_advisory_xact_lock(210,hashtext(:email))")
                .param("email",email).query(Object.class).single();
        requireActive(subject);
        if(jdbc.sql("SELECT EXISTS(SELECT 1 FROM platform.email_identity WHERE email_normalized=:email OR subject_id=:subject)")
                .param("email",email).param("subject",subject).query(Boolean.class).single())
            throw conflict("EMAIL_ALREADY_BOUND","邮箱或账号已绑定邮箱");
        jdbc.sql("""
                INSERT INTO platform.email_identity(email_normalized,subject_id,email_display,verified_at)
                VALUES(:email,:subject,:display,CASE WHEN :verified THEN now() ELSE NULL END)
                """).param("email",email).param("subject",subject).param("display",address.strip())
                .param("verified",verified).update();
    }

    @Transactional
    public void verifyBinding(String subject,String verifiedAddress) {
        String email=EmailChallengeService.normalize(verifiedAddress);
        int count=jdbc.sql("""
                UPDATE platform.email_identity SET verified_at=coalesce(verified_at,now()),updated_at=now()
                WHERE subject_id=:subject AND email_normalized=:email
                """).param("subject",subject).param("email",email).update();
        if(count!=1)throw conflict("EMAIL_BINDING_MISMATCH","验证邮箱与账号登记邮箱不一致");
    }

    @Transactional(readOnly=true)
    public Login login(String address) {
        String email=EmailChallengeService.normalize(address);
        var identity=jdbc.sql("SELECT subject_id,verified_at IS NOT NULL FROM platform.email_identity WHERE email_normalized=:email")
                .param("email",email).query((r,n)->new Identity(r.getString(1),r.getBoolean(2))).optional()
                .orElseThrow(()->new AccessDeniedException("EMAIL_ACCOUNT_UNAVAILABLE","邮箱未绑定有效账号，请先注册或绑定"));
        if(!identity.verified())throw new AccessDeniedException("EMAIL_NOT_VERIFIED","邮箱尚未验证，不能用于登录");
        requireActive(identity.subject());
        long version=jdbc.sql("SELECT session_version FROM platform.security_user WHERE subject_id=:subject")
                .param("subject",identity.subject()).query(Long.class).single();
        return new Login(identity.subject(),version);
    }

    private void requireActive(String subject) {
        boolean active=jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM platform.security_user
                  WHERE subject_id=:subject AND enabled AND account_status='ACTIVE'
                    AND employment_status='ACTIVE'
                    AND (termination_effective_at IS NULL OR termination_effective_at>now()))
                """).param("subject",subject).query(Boolean.class).single();
        if(!active)throw new AccessDeniedException("EMAIL_ACCOUNT_UNAVAILABLE","账号不可用，请联系管理员");
    }
    private record Identity(String subject,boolean verified) {}
    private static ClientRequestException conflict(String code,String message){return new ClientRequestException(code,message);}
}
