package com.cofco.qiqihar.graintrade.identity.application;

import com.cofco.qiqihar.graintrade.shared.application.*;
import com.cofco.qiqihar.graintrade.shared.security.application.SecurityPrincipalRepository;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PhoneIdentityService {
    public enum RegionChoice { PHONE, ORIGINAL }
    public record Region(String code,String name) {}
    public record Preview(UUID ticketId,String phoneAccount,String originalAccount,
            List<Region> phoneRegions,List<Region> originalRegions) {}
    public record Login(String subject,long sessionVersion) {}
    private final JdbcClient jdbc;
    private final EmployeeRegistrationService registration;
    private final IdentitySessionInvalidator sessions;
    private final SecurityPrincipalRepository principals;
    public PhoneIdentityService(JdbcClient jdbc,EmployeeRegistrationService registration,
            IdentitySessionInvalidator sessions,SecurityPrincipalRepository principals) {
        this.jdbc=jdbc;this.registration=registration;this.sessions=sessions;this.principals=principals;
    }
    @Transactional
    public IdentityActivationResult register(String issuer,String providerSubject,String username,
            EmployeeAssignment assignment,String verifiedPhone) {
        lock();
        var result=registration.register(issuer,providerSubject,username,assignment);
        bind(username,verifiedPhone);
        return result;
    }
    @Transactional(readOnly=true)
    public void requireUnboundPhone(String phone) {
        SmsChallengeService.validatePhone(phone);
        if (jdbc.sql("SELECT EXISTS(SELECT 1 FROM platform.phone_identity WHERE phone=:phone)")
                .param("phone",phone).query(Boolean.class).single())
            throw conflict("PHONE_ALREADY_BOUND","该手机号已绑定账号，请直接使用短信验证码登录");
    }
    @Transactional
    public void bind(String subject,String verifiedPhone) {
        SmsChallengeService.validatePhone(verifiedPhone);
        lock();requireActive(subject);
        if(jdbc.sql("SELECT count(*) FROM platform.phone_identity WHERE phone=:phone OR subject_id=:subject")
                .param("phone",verifiedPhone).param("subject",subject).query(Long.class).single()>0)
            throw conflict("PHONE_ALREADY_BOUND","手机号或账号已绑定，请通过账号合并入口处理");
        jdbc.sql("INSERT INTO platform.phone_identity(phone,subject_id) VALUES(:phone,:subject)")
                .param("phone",verifiedPhone).param("subject",subject).update();
    }
    @Transactional(readOnly=true)
    public Login login(String verifiedPhone) {
        String subject=phoneSubject(verifiedPhone);
        requireActive(subject);
        return new Login(subject,jdbc.sql("SELECT session_version FROM platform.security_user WHERE subject_id=:subject")
                .param("subject",subject).query(Long.class).single());
    }
    @Transactional
    public Preview preview(String target,String phone,String session) {
        lock();
        String source=phoneSubject(phone);
        validatePair(source,target);
        UUID ticket=UUID.randomUUID();
        jdbc.sql("INSERT INTO platform.phone_merge_ticket(ticket_id,source_subject,target_subject,session_hash,snapshot,expires_at) VALUES(:id,:source,:target,:session,:snapshot,now()+interval '5 minutes')")
                .param("id",ticket).param("source",source).param("target",target)
                .param("session",SmsChallengeService.hash(session)).param("snapshot",snapshot(source,target)).update();
        return new Preview(ticket,source,target,regions(source),regions(target));
    }
    @Transactional
    public void merge(String target,UUID ticket,String session,RegionChoice choice) {
        if(ticket==null||choice==null)throw conflict("MERGE_CHOICE_REQUIRED","请选择保留哪一个账号的负责区域");
        lock();
        var rows=jdbc.sql("SELECT source_subject,snapshot FROM platform.phone_merge_ticket WHERE ticket_id=:id AND target_subject=:target AND session_hash=:session AND NOT consumed AND expires_at>now() FOR UPDATE")
                .param("id",ticket).param("target",target).param("session",SmsChallengeService.hash(session))
                .query((r,n)->List.of(r.getString(1),r.getString(2))).list();
        if(rows.isEmpty())throw conflict("MERGE_EXPIRED","合并验证已过期或已使用，请重新验证");
        String source=rows.getFirst().getFirst();
        validatePair(source,target);
        if(!snapshot(source,target).equals(rows.getFirst().get(1)))throw conflict("MERGE_CHANGED","账号或区域已变化，请重新预览");
        List<Region> sourceRegions=regions(source),targetRegions=regions(target);
        List<String> selected=(choice==RegionChoice.PHONE?sourceRegions:targetRegions).stream().map(Region::code).toList();
        if(selected.isEmpty())throw conflict("MERGE_REGION_REQUIRED","所选账号没有有效负责区域");
        AccountRegionPolicy.requireAtMostTen(target,selected,principals.findEnabled(target).map(com.cofco.qiqihar.graintrade.shared.security.domain.SecurityPrincipal::isRootAdministrator).orElse(false));
        validateUnit(target,selected);
        List<String> responsibilityRegions=jdbc.sql("SELECT region_code FROM platform.region_responsibility WHERE subject_id IN (:subjects)")
                .param("subjects",List.of(source,target)).query(String.class).list();
        // The old source stays in history; it cannot authenticate or reserve any region.
        jdbc.sql("UPDATE platform.security_user SET enabled=false,account_status='REVOKED',version=version+1,updated_at=now() WHERE subject_id=:subject")
                .param("subject",source).update();
        jdbc.sql("UPDATE platform.security_user_region_scope SET valid_until=now() WHERE subject_id IN (:subjects) AND (valid_until IS NULL OR valid_until>now())")
                .param("subjects",List.of(source,target)).update();
        jdbc.sql("UPDATE platform.region_responsibility SET subject_id=NULL,version=version+1,updated_by=:target,updated_at=now(),reason='手机号账号合并释放区域' WHERE subject_id IN (:subjects)")
                .param("target",target).param("subjects",List.of(source,target)).update();
        for(String region:selected) {
            if(!jdbc.sql("SELECT platform.employee_region_available(:region,:target)")
                    .param("region",region).param("target",target).query(Boolean.class).single())
                throw conflict("MERGE_REGION_CONFLICT","所选区域已由其他有效账号负责，合并未执行");
            jdbc.sql("INSERT INTO platform.security_user_region_scope(subject_id,region_code,valid_from,granted_by,granted_at,review_due_at) VALUES(:target,:region,now(),:target,now(),now()+interval '1 year')")
                    .param("target",target).param("region",region).update();
        }
        // Transfer explicit responsibility only where either account previously held it.
        // Access scope alone must not silently create a new responsibility assignment.
        jdbc.sql("UPDATE platform.region_responsibility SET subject_id=:target,version=version+1,updated_at=now(),reason='手机号账号合并迁移区域' WHERE region_code IN (:regions) AND subject_id IS NULL AND updated_by=:target AND reason='手机号账号合并释放区域'")
                .param("target",target).param("regions",selected).update();
        var affected=new TreeSet<String>();
        sourceRegions.forEach(r->affected.add(r.code()));targetRegions.forEach(r->affected.add(r.code()));
        jdbc.sql("""
                WITH RECURSIVE covered(code,chosen,explicit_owner) AS (
                    SELECT code,code IN (:selected),code IN (:responsibilityRegions) FROM platform.region WHERE code IN (:affected)
                    UNION ALL SELECT child.code,parent.chosen,parent.explicit_owner FROM platform.region child JOIN covered parent ON child.parent_code=parent.code
                ), changes AS (
                    SELECT p.sample_point_id,
                        CASE WHEN bool_or(c.chosen AND c.explicit_owner) THEN CAST(:target AS varchar)
                             WHEN p.maintainer_subject_id IN (:subjects) THEN
                                CASE WHEN bool_or(c.chosen) THEN coalesce(platform.region_responsible_subject(p.region_code),CAST(:target AS varchar))
                                     ELSE platform.region_responsible_subject(p.region_code) END
                             ELSE p.maintainer_subject_id END AS next_subject
                    FROM registry.sample_point p JOIN covered c ON c.code=p.region_code
                    WHERE p.deletion_state='ACTIVE'
                    GROUP BY p.sample_point_id,p.maintainer_subject_id,p.region_code
                )
                UPDATE registry.sample_point p SET maintainer_subject_id=c.next_subject,
                    version=p.version+1,updated_by=:target,updated_at=clock_timestamp()
                FROM changes c WHERE c.sample_point_id=p.sample_point_id
                    AND p.maintainer_subject_id IS DISTINCT FROM c.next_subject
                """).param("selected",selected).param("affected",affected).param("target",target)
                .param("subjects",List.of(source,target))
                .param("responsibilityRegions",responsibilityRegions.isEmpty()?List.of("__none"):responsibilityRegions).update();
        jdbc.sql("UPDATE platform.phone_identity SET subject_id=:target,verified_at=now() WHERE subject_id=:source")
                .param("target",target).param("source",source).update();
        jdbc.sql("UPDATE platform.security_user SET version=version+1,updated_at=now() WHERE subject_id=:target")
                .param("target",target).update();
        jdbc.sql("INSERT INTO platform.phone_merge_history(merge_id,source_subject,target_subject,region_choice,source_regions,target_regions) VALUES(:id,:source,:target,:choice,:sourceRegions,:targetRegions)")
                .param("id",ticket).param("source",source).param("target",target).param("choice",choice.name())
                .param("sourceRegions",sourceRegions.toString()).param("targetRegions",targetRegions.toString()).update();
        jdbc.sql("UPDATE platform.phone_merge_ticket SET consumed=true WHERE ticket_id=:id").param("id",ticket).update();
        sessions.invalidate(source,"PHONE_ACCOUNT_MERGED");
        sessions.invalidate(target,"PHONE_ACCOUNT_MERGED");
    }
    private void lock() {
        jdbc.sql("SELECT platform.lock_region_responsibility_change()").query(Object.class).single();
        jdbc.sql("SELECT pg_advisory_xact_lock(184,185)").query(Object.class).single();
        jdbc.sql("LOCK TABLE platform.phone_identity IN SHARE ROW EXCLUSIVE MODE").update();
    }
    private void validatePair(String source,String target) {
        if(source.equals(target)||source.equals("admin"))throw conflict("MERGE_NOT_ALLOWED","请选择另一个普通手机账号进行合并");
        requireActive(source);requireActive(target);
        if(jdbc.sql("SELECT count(*) FROM platform.phone_identity WHERE subject_id=:target")
                .param("target",target).query(Long.class).single()!=0)
            throw conflict("PHONE_ALREADY_BOUND","原账号已绑定手机号，不能覆盖绑定");
    }
    private void requireActive(String subject) {
        if(principals.findEnabled(subject).filter(p->!p.roleCodes().isEmpty()).isEmpty())
            throw new AccessDeniedException("PHONE_ACCOUNT_UNAVAILABLE","账号不可用，请联系管理员");
    }
    private String phoneSubject(String phone) {
        return jdbc.sql("SELECT subject_id FROM platform.phone_identity WHERE phone=:phone")
                .param("phone",phone).query(String.class).optional()
                .orElseThrow(()->new AccessDeniedException("PHONE_ACCOUNT_UNAVAILABLE","手机号未绑定有效账号，请先注册或绑定"));
    }
    private List<Region> regions(String subject) {
        return jdbc.sql("SELECT r.code,r.name FROM platform.region r JOIN (SELECT region_code FROM platform.security_user_region_scope WHERE subject_id=:subject AND valid_from<=now() AND (valid_until IS NULL OR valid_until>now()) AND (review_due_at IS NULL OR review_due_at>now()) UNION SELECT region_code FROM platform.region_responsibility WHERE subject_id=:subject) s ON s.region_code=r.code ORDER BY r.code")
                .param("subject",subject).query((r,n)->new Region(r.getString(1),r.getString(2))).list();
    }
    private String snapshot(String source,String target) {
        String users=jdbc.sql("SELECT subject_id||':'||version||':'||session_version||':'||work_unit_code FROM platform.security_user WHERE subject_id IN (:subjects) ORDER BY subject_id")
                .param("subjects",List.of(source,target)).query(String.class).list().toString();
        String phone=jdbc.sql("SELECT phone||':'||subject_id FROM platform.phone_identity WHERE subject_id IN (:subjects) ORDER BY subject_id")
                .param("subjects",List.of(source,target)).query(String.class).list().toString();
        return SmsChallengeService.hash(users+phone+regions(source)+regions(target));
    }
    private void validateUnit(String target,List<String> regions) {
        long allowed=jdbc.sql("WITH RECURSIVE permitted(code) AS (SELECT s.region_code FROM platform.work_unit_region_scope s JOIN platform.security_user u ON u.work_unit_code=s.work_unit_code WHERE u.subject_id=:target UNION SELECT r.code FROM platform.region r JOIN permitted p ON r.parent_code=p.code) SELECT count(*) FROM permitted WHERE code IN (:regions)")
                .param("target",target).param("regions",regions).query(Long.class).single();
        if(allowed!=regions.size())throw conflict("MERGE_UNIT_SCOPE","所选区域超出原账号单位范围，合并未执行");
    }
    private static ConflictException conflict(String code,String message){return new ConflictException(code,message);}
}
