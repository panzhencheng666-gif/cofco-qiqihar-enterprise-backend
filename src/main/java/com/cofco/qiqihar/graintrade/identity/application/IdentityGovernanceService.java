package com.cofco.qiqihar.graintrade.identity.application;

import com.cofco.qiqihar.graintrade.shared.application.AccessDeniedException;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.application.ConflictException;
import com.cofco.qiqihar.graintrade.shared.application.ResourceNotFoundException;
import com.cofco.qiqihar.graintrade.shared.audit.application.BusinessAuditRecorder;
import com.cofco.qiqihar.graintrade.shared.security.application.AccessControl;
import com.cofco.qiqihar.graintrade.shared.security.domain.SecurityPrincipal;
import java.time.Clock;
import java.time.Duration;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdentityGovernanceService {
    private static final String ACCOUNT_OWNER_ROLE="ACCOUNT_OWNER";
    private static final String SYSTEM_ADMINISTRATOR_ROLE="SYSTEM_ADMIN";
    private static final String REPORTER_ROLE="BUSINESS_OPERATOR";
    private static final String ADMINISTRATOR_ROLE="BUSINESS_REVIEWER";
    private static final Set<String> ASSIGNABLE_BUSINESS_ROLES=Set.of(REPORTER_ROLE,ADMINISTRATOR_ROLE);
    private static final Set<String> ASSIGNABLE_WORK_UNITS=Set.of(
            "QIQIHAR_BUSINESS","NEHE_DEPOT","KESHAN_DEPOT",
            "KEDONG_DEPOT","LONGZHEN_DEPOT","CHENGJISIHAN_DEPOT");
    private static final Set<String> ACCOUNT_STATUSES=Set.of("INVITED","ACTIVE","LOCKED","SUSPENDED","REVOKED");
    private static final Set<String> EMPLOYMENT_STATUSES=Set.of("ACTIVE","LEAVE","TERMINATED");
    private final IdentityGovernanceRepository repository;
    private final AccessControl access;
    private final BusinessAuditRecorder audit;
    private final Clock clock;
    private final IdentityInvitationTokenCodec invitationTokens;
    private final IdentitySessionInvalidator sessions;

    public IdentityGovernanceService(IdentityGovernanceRepository repository,AccessControl access,
            BusinessAuditRecorder audit,Clock clock,IdentityInvitationTokenCodec invitationTokens,
            IdentitySessionInvalidator sessions) {
        this.repository=repository;this.access=access;this.audit=audit;this.clock=clock;
        this.invitationTokens=invitationTokens;this.sessions=sessions;
    }

    @Transactional(readOnly=true)
    public List<EmployeeProfile> employees() {
        SecurityPrincipal actor=access.require("IDENTITY_READ",null);
        return repository.findAll(systemAdministrator(actor)?null:actor.workUnitCode()).stream()
                .map(IdentityGovernanceService::businessProfile).toList();
    }

    @Transactional(readOnly=true)
    public EmployeeProfile employee(String subjectId) {
        SecurityPrincipal actor=access.require("IDENTITY_READ",null);
        return businessProfile(required(subjectId,actor));
    }

    @Transactional(readOnly=true)
    public AssignmentOptions assignmentOptions(String workUnitCode) {
        SecurityPrincipal actor=access.require("IDENTITY_READ",null);
        if(blank(workUnitCode))throw invalid();
        requireAssignableWorkUnit(workUnitCode);
        requireWorkUnit(actor,workUnitCode);
        AssignmentOptions available=repository.assignmentOptions(workUnitCode);
        AssignmentOptions options=new AssignmentOptions(
                available.workUnits().stream()
                        .filter(unit->ASSIGNABLE_WORK_UNITS.contains(unit.code())).toList(),
                available.roles().stream().filter(role->ASSIGNABLE_BUSINESS_ROLES.contains(role.code())).toList(),
                available.positions(),available.regionCodes(),available.regions());
        if(systemAdministrator(actor))return options;
        return new AssignmentOptions(
                options.workUnits().stream().filter(unit->unit.code().equals(actor.workUnitCode())).toList(),
                options.roles().stream().filter(role->!role.code().equals("SYSTEM_ADMIN")).toList(),
                options.positions(),options.regionCodes(),options.regions());
    }

    @Transactional
    public IdentityInvitationReceipt invite(String idempotencyKey,String subjectId,
            String deliveryAddress,EmployeeAssignment requested) {
        SecurityPrincipal actor=access.require("IDENTITY_ADMIN",null);
        requireSubject(subjectId);
        requireIdempotencyKey(idempotencyKey);
        requireDeliveryAddress(deliveryAddress);
        if(requested==null)throw invalid();
        EmployeeAssignment assignment=new EmployeeAssignment(requested.displayName(),requested.workUnitCode(),
                "INVITED","ACTIVE",requested.roleCodes(),requested.positionCodes(),requested.regionCodes());
        requireAssignableWorkUnit(assignment.workUnitCode());
        requireWorkUnit(actor,assignment.workUnitCode());
        requireRoleAssignment(assignment.roleCodes());
        validate(assignment);
        String requestSha256=invitationTokens.sha256(String.join("\n",
                subjectId,deliveryAddress.strip().toLowerCase(java.util.Locale.ROOT),
                assignment.displayName(),assignment.workUnitCode(),
                String.join(",",assignment.roleCodes()),String.join(",",assignment.positionCodes()),
                String.join(",",assignment.regionCodes())));
        IdentityInvitation existing=repository.findInvitationByIdempotency(actor.subjectId(),idempotencyKey)
                .orElse(null);
        if(existing!=null) {
            if(!existing.requestSha256().equals(requestSha256))throw new ConflictException(
                    IdentityLifecycleContract.ERROR_IDEMPOTENCY_CONFLICT,
                    "相同幂等键已用于其他邀请请求");
            EmployeeProfile employee=repository.find(existing.subjectId(),
                    systemAdministrator(actor)?null:actor.workUnitCode()).orElseThrow(
                            ()->new ConflictException(IdentityLifecycleContract.ERROR_IDEMPOTENCY_CONFLICT,
                                    "相同幂等键已用于其他邀请请求"));
            return IdentityInvitationReceipt.from(businessProfile(employee),existing,true);
        }
        if(repository.exists(subjectId))throw new ConflictException(
                "IDENTITY_SUBJECT_EXISTS","员工账号已存在");
        EmployeeProfile created=repository.create(subjectId,assignment,actor.subjectId());
        String token=invitationTokens.generateToken();
        IdentityInvitation invitation=repository.createInvitation(
                UUID.randomUUID(),subjectId,invitationTokens.sha256(token),
                invitationTokens.encryptDeliveryPayload(deliveryAddress.strip(),token),
                invitationTokens.sha256(deliveryAddress.strip().toLowerCase(java.util.Locale.ROOT)),
                clock.instant().plus(Duration.ofHours(24)),actor.subjectId(),idempotencyKey,requestSha256);
        audit.record(actor,assignment.workUnitCode(),"SECURITY_USER",subjectId,
                IdentityLifecycleContract.AUDIT_INVITED,clock.instant(),
                "{\"accountStatus\":\"INVITED\",\"deliveryStatus\":\"QUEUED\","
                        +"\"contractVersion\":\""+IdentityInvitationReceipt.CONTRACT_VERSION+"\"}");
        return IdentityInvitationReceipt.from(businessProfile(created),invitation,false);
    }

    @Transactional
    public IdentityActivationResult activate(String token,String issuerUri,String providerSubject) {
        if(token==null||token.length()<16||token.length()>256||blank(issuerUri)||blank(providerSubject)
                ||providerSubject.length()>255||!trustedHttpsIssuer(issuerUri))throw invalidInvitation();
        EmployeeProfile activated;
        try {
            activated=repository.activateInvitation(
                    invitationTokens.sha256(token),issuerUri,providerSubject,clock.instant())
                    .orElseThrow(IdentityGovernanceService::invalidInvitation);
        } catch(org.springframework.dao.DataIntegrityViolationException conflict) {
            throw invalidInvitation();
        }
        SecurityPrincipal principal=new SecurityPrincipal(
                activated.subjectId(),activated.displayName(),activated.workUnitCode(),
                Set.of(),Set.copyOf(activated.regionCodes()));
        audit.record(principal,activated.workUnitCode(),"SECURITY_USER",activated.subjectId(),
                IdentityLifecycleContract.AUDIT_ACTIVATED,clock.instant(),
                "{\"bindingStatus\":\"ACTIVE\",\"issuerSha256\":\""
                        +invitationTokens.sha256(issuerUri)+"\"}");
        return IdentityActivationResult.active(activated.subjectId());
    }

    @Transactional
    public IdentityInvitationReceipt revokeInvitation(UUID invitationId) {
        SecurityPrincipal actor=access.require("IDENTITY_ADMIN",null);
        IdentityInvitation invitation=repository.findInvitation(invitationId)
                .orElseThrow(IdentityGovernanceService::invitationNotFound);
        EmployeeProfile employee=repository.find(invitation.subjectId(),
                systemAdministrator(actor)?null:actor.workUnitCode())
                .orElseThrow(IdentityGovernanceService::invitationNotFound);
        if(invitation.invitationStatus().equals("ACTIVATED"))throw new ConflictException(
                IdentityLifecycleContract.ERROR_INVITATION_STATE_CONFLICT,"已激活邀请不能撤销");
        IdentityInvitation revoked=repository.revokeInvitation(invitationId,clock.instant())
                .orElseThrow(IdentityGovernanceService::invitationNotFound);
        audit.record(actor,employee.workUnitCode(),"SECURITY_USER",employee.subjectId(),
                IdentityLifecycleContract.AUDIT_REVOKED,clock.instant(),
                "{\"invitationId\":\""+invitationId+"\"}");
        return IdentityInvitationReceipt.from(businessProfile(employee),revoked,false);
    }

    @Transactional(readOnly=true)
    public IdentityInvitationReceipt currentInvitation(String subjectId) {
        SecurityPrincipal actor=access.require("IDENTITY_ADMIN",null);
        EmployeeProfile employee=required(subjectId,actor);
        IdentityInvitation invitation=repository.findCurrentInvitation(subjectId)
                .orElseThrow(IdentityGovernanceService::invitationNotFound);
        return IdentityInvitationReceipt.from(businessProfile(employee),invitation,false);
    }

    @Transactional
    public IdentityInvitationReceipt reissueInvitation(
            String idempotencyKey,String subjectId,String deliveryAddress) {
        SecurityPrincipal actor=access.require("IDENTITY_ADMIN",null);
        requireIdempotencyKey(idempotencyKey);
        requireDeliveryAddress(deliveryAddress);
        EmployeeProfile employee=required(subjectId,actor);
        if(!employee.accountStatus().equals("INVITED")||!employee.employmentStatus().equals("ACTIVE"))
            throw new ConflictException(IdentityLifecycleContract.ERROR_INVITATION_STATE_CONFLICT,
                    "当前账号状态不能重新邀请");
        String requestFingerprint=invitationFingerprint(subjectId,deliveryAddress,new EmployeeAssignment(
                employee.displayName(),employee.workUnitCode(),"INVITED",employee.employmentStatus(),
                employee.roles().stream().map(EmployeeProfile.Grant::code).toList(),
                employee.positions().stream().map(EmployeeProfile.Position::code).toList(),
                employee.regionCodes()));
        IdentityInvitation existing=repository.findInvitationByIdempotency(actor.subjectId(),idempotencyKey)
                .orElse(null);
        if(existing!=null) {
            if(!existing.requestSha256().equals(requestFingerprint)||!existing.subjectId().equals(subjectId))
                throw new ConflictException(IdentityLifecycleContract.ERROR_IDEMPOTENCY_CONFLICT,
                        "相同幂等键已用于其他邀请请求");
            return IdentityInvitationReceipt.from(businessProfile(employee),existing,true);
        }
        repository.revokePendingInvitations(subjectId,clock.instant());
        IdentityInvitation invitation=createInvitation(
                subjectId,deliveryAddress,actor.subjectId(),idempotencyKey,requestFingerprint);
        audit.record(actor,employee.workUnitCode(),"SECURITY_USER",subjectId,
                IdentityLifecycleContract.AUDIT_REINVITED,
                clock.instant(),"{\"deliveryStatus\":\"QUEUED\"}");
        return IdentityInvitationReceipt.from(businessProfile(employee),invitation,false);
    }

    @Transactional
    public EmployeeProfile update(String subjectId,long expectedVersion,EmployeeAssignment assignment) {
        SecurityPrincipal actor=access.require("IDENTITY_ADMIN",null);
        if(actor.subjectId().equals(subjectId))throw new AccessDeniedException(
                "IDENTITY_SELF_ADMINISTRATION_DENIED","不能修改本人的账号或授权");
        EmployeeProfile current=required(subjectId,actor);
        if(!systemAdministrator(actor)&&current.roles().stream()
                .anyMatch(role->role.code().equals(ACCOUNT_OWNER_ROLE)))
            throw new AccessDeniedException(
                    "ACCOUNT_OWNER_ADMINISTRATION_DENIED","平台唯一所有者只能由系统管理员维护");
        if(!systemAdministrator(actor)&&current.roles().stream().anyMatch(role->role.code().equals("SYSTEM_ADMIN")))
            throw roleDenied();
        requireAssignableWorkUnit(assignment.workUnitCode());
        requireWorkUnit(actor,assignment.workUnitCode());
        requireRoleAssignment(assignment.roleCodes());
        validate(assignment);
        validateTransition(current,assignment);
        EmployeeProfile updated=repository.update(subjectId,expectedVersion,assignment,actor.subjectId())
                .orElseThrow(() -> new ConflictException(
                        "IDENTITY_VERSION_CONFLICT","员工账号信息已发生变化，请刷新后重试"));
        sessions.invalidate(subjectId,"IDENTITY_CHANGED");
        String action=assignment.employmentStatus().equals("TERMINATED")
                ? "SECURITY_USER_TERMINATED" : "SECURITY_USER_UPDATED";
        audit.record(actor,assignment.workUnitCode(),"SECURITY_USER",subjectId,action,clock.instant(),
                "{\"accountStatus\":\""+assignment.accountStatus()
                        +"\",\"employmentStatus\":\""+assignment.employmentStatus()+"\"}");
        return businessProfile(updated);
    }

    private EmployeeProfile required(String subjectId,SecurityPrincipal actor) {
        return repository.find(subjectId,systemAdministrator(actor)?null:actor.workUnitCode())
                .orElseThrow(() -> new ResourceNotFoundException(
                        IdentityLifecycleContract.ERROR_SUBJECT_NOT_FOUND,"员工账号不存在"));
    }

    private void validate(EmployeeAssignment assignment) {
        if(assignment==null||blank(assignment.displayName())||blank(assignment.workUnitCode())
                ||!ACCOUNT_STATUSES.contains(assignment.accountStatus())
                ||!EMPLOYMENT_STATUSES.contains(assignment.employmentStatus())
                ||assignment.displayName().codePointCount(0,assignment.displayName().length())>160
                ||duplicates(assignment.roleCodes())||duplicates(assignment.positionCodes())
                ||duplicates(assignment.regionCodes())||!repository.validAssignment(assignment))throw invalid();
    }

    private static void validateTransition(EmployeeProfile current,EmployeeAssignment next) {
        boolean accountAllowed=switch(current.accountStatus()) {
            case "INVITED" -> Set.of("INVITED","REVOKED").contains(next.accountStatus());
            case "ACTIVE" -> Set.of("ACTIVE","LOCKED","SUSPENDED","REVOKED").contains(next.accountStatus());
            case "LOCKED","SUSPENDED" -> Set.of("ACTIVE","LOCKED","SUSPENDED","REVOKED").contains(next.accountStatus());
            case "REVOKED" -> next.accountStatus().equals("REVOKED");
            default -> false;
        };
        boolean employmentAllowed=switch(current.employmentStatus()) {
            case "ACTIVE" -> Set.of("ACTIVE","LEAVE","TERMINATED").contains(next.employmentStatus());
            case "LEAVE" -> Set.of("ACTIVE","LEAVE","TERMINATED").contains(next.employmentStatus());
            case "TERMINATED" -> next.employmentStatus().equals("TERMINATED");
            default -> false;
        };
        if(current.accountStatus().equals("INVITED")&&next.accountStatus().equals("ACTIVE"))
            throw new ConflictException("IDENTITY_OIDC_ACTIVATION_REQUIRED","受邀账号必须通过企业身份认证激活");
        if(!accountAllowed||!employmentAllowed)throw new ConflictException(
                "IDENTITY_LIFECYCLE_CONFLICT","不允许执行当前账号状态变更");
    }

    private static boolean duplicates(List<String> values){return new HashSet<>(values).size()!=values.size();}
    private static boolean systemAdministrator(SecurityPrincipal actor){
        return actor.roleCodes().contains("SYSTEM_ADMIN");
    }
    private static void requireWorkUnit(SecurityPrincipal actor,String workUnitCode){
        if(!systemAdministrator(actor)&&!actor.workUnitCode().equals(workUnitCode))throw new AccessDeniedException(
                "ACCESS_WORK_UNIT_DENIED","无权访问其他工作单位");
    }
    private static void requireAssignableWorkUnit(String workUnitCode){
        if(!ASSIGNABLE_WORK_UNITS.contains(workUnitCode))throw invalid();
    }
    private static void requireRoleAssignment(List<String> roleCodes){
        if(roleCodes.contains(ACCOUNT_OWNER_ROLE))throw new AccessDeniedException(
                "ACCOUNT_OWNER_ASSIGNMENT_DENIED","平台唯一所有者角色不能通过员工授权分配");
        if(roleCodes.contains(SYSTEM_ADMINISTRATOR_ROLE))throw roleDenied();
        if(roleCodes.size()!=1||!ASSIGNABLE_BUSINESS_ROLES.contains(roleCodes.get(0)))throw roleDenied();
    }

    private static EmployeeProfile businessProfile(EmployeeProfile profile) {
        if(profile.roles().isEmpty())return profile;
        boolean administrator=profile.roles().stream().anyMatch(role->Set.of(
                ADMINISTRATOR_ROLE,SYSTEM_ADMINISTRATOR_ROLE,ACCOUNT_OWNER_ROLE,
                "IDENTITY_ADMIN","ACCESS_REVIEWER").contains(role.code()));
        EmployeeProfile.Grant role=administrator
                ? new EmployeeProfile.Grant(ADMINISTRATOR_ROLE,"管理员")
                : new EmployeeProfile.Grant(REPORTER_ROLE,"填报员");
        return new EmployeeProfile(profile.subjectId(),profile.displayName(),profile.workUnitCode(),
                profile.workUnitName(),profile.accountStatus(),profile.employmentStatus(),List.of(role),
                profile.positions(),profile.regionCodes(),profile.version());
    }
    private static AccessDeniedException roleDenied(){return new AccessDeniedException(
            "ACCESS_ROLE_ASSIGNMENT_DENIED","当前账号不能授予系统管理员角色");}
    private static void requireSubject(String subjectId){
        if(blank(subjectId)||subjectId.length()>120||!subjectId.matches("[A-Za-z0-9._:@-]+"))throw invalid();
    }
    private static void requireIdempotencyKey(String value){
        if(value==null||!value.matches("^[A-Za-z0-9][A-Za-z0-9._:-]{7,159}$"))throw new ClientRequestException(
                IdentityLifecycleContract.ERROR_INVALID_IDEMPOTENCY_KEY,"幂等键格式不正确");
    }
    private static void requireDeliveryAddress(String value){
        if(value==null||value.length()>254||!value.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"))
            throw new ClientRequestException(IdentityLifecycleContract.ERROR_INVALID_DELIVERY_ADDRESS,
                    "邀请送达地址格式不正确");
    }
    private static boolean trustedHttpsIssuer(String value){
        try {
            URI uri=new URI(value);
            return "https".equalsIgnoreCase(uri.getScheme())&&uri.getHost()!=null
                    &&uri.getUserInfo()==null&&uri.getFragment()==null;
        } catch(URISyntaxException invalid){return false;}
    }
    private static boolean blank(String value){return value==null||value.isBlank();}
    private static ClientRequestException invalid(){return new ClientRequestException(
            "INVALID_IDENTITY_ASSIGNMENT","员工账号或授权信息不完整");}
    private static ClientRequestException invalidInvitation(){return new ClientRequestException(
            IdentityLifecycleContract.ERROR_INVITATION_INVALID,"邀请凭证无效或已失效");}
    private static ResourceNotFoundException invitationNotFound(){return new ResourceNotFoundException(
            IdentityLifecycleContract.ERROR_INVITATION_NOT_FOUND,"邀请不存在");}

    private String invitationFingerprint(
            String subjectId,String deliveryAddress,EmployeeAssignment assignment) {
        return invitationTokens.sha256(String.join("\n",
                subjectId,deliveryAddress.strip().toLowerCase(java.util.Locale.ROOT),
                assignment.displayName(),assignment.workUnitCode(),
                String.join(",",assignment.roleCodes()),String.join(",",assignment.positionCodes()),
                String.join(",",assignment.regionCodes())));
    }

    private IdentityInvitation createInvitation(String subjectId,String deliveryAddress,
            String actorSubjectId,String idempotencyKey,String requestFingerprint) {
        String token=invitationTokens.generateToken();
        return repository.createInvitation(
                UUID.randomUUID(),subjectId,invitationTokens.sha256(token),
                invitationTokens.encryptDeliveryPayload(deliveryAddress.strip(),token),
                invitationTokens.sha256(deliveryAddress.strip().toLowerCase(java.util.Locale.ROOT)),
                clock.instant().plus(Duration.ofHours(24)),actorSubjectId,idempotencyKey,requestFingerprint);
    }
}
