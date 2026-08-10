package com.cofco.qiqihar.graintrade.identity.application;

import com.cofco.qiqihar.graintrade.shared.application.AccessDeniedException;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.application.ConflictException;
import com.cofco.qiqihar.graintrade.shared.application.ResourceNotFoundException;
import com.cofco.qiqihar.graintrade.shared.audit.application.BusinessAuditRecorder;
import com.cofco.qiqihar.graintrade.shared.security.application.AccessControl;
import com.cofco.qiqihar.graintrade.shared.security.domain.SecurityPrincipal;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdentityGovernanceService {
    private static final Set<String> ACCOUNT_STATUSES=Set.of("INVITED","ACTIVE","LOCKED","SUSPENDED","REVOKED");
    private static final Set<String> EMPLOYMENT_STATUSES=Set.of("ACTIVE","LEAVE","TERMINATED");
    private final IdentityGovernanceRepository repository;
    private final AccessControl access;
    private final BusinessAuditRecorder audit;
    private final Clock clock;

    public IdentityGovernanceService(IdentityGovernanceRepository repository,AccessControl access,
            BusinessAuditRecorder audit,Clock clock) {
        this.repository=repository;this.access=access;this.audit=audit;this.clock=clock;
    }

    @Transactional(readOnly=true)
    public List<EmployeeProfile> employees() {
        SecurityPrincipal actor=access.require("IDENTITY_READ",null);
        return repository.findAll(systemAdministrator(actor)?null:actor.workUnitCode());
    }

    @Transactional(readOnly=true)
    public EmployeeProfile employee(String subjectId) {
        SecurityPrincipal actor=access.require("IDENTITY_READ",null);
        return required(subjectId,actor);
    }

    @Transactional(readOnly=true)
    public AssignmentOptions assignmentOptions(String workUnitCode) {
        SecurityPrincipal actor=access.require("IDENTITY_READ",null);
        if(blank(workUnitCode))throw invalid();
        requireWorkUnit(actor,workUnitCode);
        AssignmentOptions options=repository.assignmentOptions(workUnitCode);
        if(systemAdministrator(actor))return options;
        return new AssignmentOptions(
                options.workUnits().stream().filter(unit->unit.code().equals(actor.workUnitCode())).toList(),
                options.roles().stream().filter(role->!role.code().equals("SYSTEM_ADMIN")).toList(),
                options.positions(),options.regionCodes());
    }

    @Transactional
    public EmployeeProfile invite(String subjectId,EmployeeAssignment requested) {
        SecurityPrincipal actor=access.require("IDENTITY_ADMIN",null);
        requireSubject(subjectId);
        if(requested==null)throw invalid();
        EmployeeAssignment assignment=new EmployeeAssignment(requested.displayName(),requested.workUnitCode(),
                "INVITED","ACTIVE",requested.roleCodes(),requested.positionCodes(),requested.regionCodes());
        requireWorkUnit(actor,assignment.workUnitCode());
        requireRoleAssignment(actor,assignment.roleCodes());
        validate(assignment);
        if(repository.exists(subjectId))throw new ConflictException(
                "IDENTITY_SUBJECT_EXISTS","员工账号已存在");
        EmployeeProfile created=repository.create(subjectId,assignment,actor.subjectId());
        audit.record(actor,assignment.workUnitCode(),"SECURITY_USER",subjectId,"SECURITY_USER_INVITED",clock.instant(),
                "{\"accountStatus\":\"INVITED\"}");
        return created;
    }

    @Transactional
    public EmployeeProfile update(String subjectId,long expectedVersion,EmployeeAssignment assignment) {
        SecurityPrincipal actor=access.require("IDENTITY_ADMIN",null);
        EmployeeProfile current=required(subjectId,actor);
        if(!systemAdministrator(actor)&&current.roles().stream().anyMatch(role->role.code().equals("SYSTEM_ADMIN")))
            throw roleDenied();
        requireWorkUnit(actor,assignment.workUnitCode());
        requireRoleAssignment(actor,assignment.roleCodes());
        validate(assignment);
        validateTransition(current,assignment);
        EmployeeProfile updated=repository.update(subjectId,expectedVersion,assignment,actor.subjectId())
                .orElseThrow(() -> new ConflictException(
                        "IDENTITY_VERSION_CONFLICT","员工账号信息已发生变化，请刷新后重试"));
        String action=assignment.employmentStatus().equals("TERMINATED")
                ? "SECURITY_USER_TERMINATED" : "SECURITY_USER_UPDATED";
        audit.record(actor,assignment.workUnitCode(),"SECURITY_USER",subjectId,action,clock.instant(),
                "{\"accountStatus\":\""+assignment.accountStatus()
                        +"\",\"employmentStatus\":\""+assignment.employmentStatus()+"\"}");
        return updated;
    }

    private EmployeeProfile required(String subjectId,SecurityPrincipal actor) {
        return repository.find(subjectId,systemAdministrator(actor)?null:actor.workUnitCode())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "IDENTITY_SUBJECT_NOT_FOUND","员工账号不存在"));
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
            case "INVITED" -> Set.of("INVITED","ACTIVE","REVOKED").contains(next.accountStatus());
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
    private static void requireRoleAssignment(SecurityPrincipal actor,List<String> roleCodes){
        if(!systemAdministrator(actor)&&roleCodes.contains("SYSTEM_ADMIN"))throw roleDenied();
    }
    private static AccessDeniedException roleDenied(){return new AccessDeniedException(
            "ACCESS_ROLE_ASSIGNMENT_DENIED","当前账号不能授予系统管理员角色");}
    private static void requireSubject(String subjectId){
        if(blank(subjectId)||subjectId.length()>120||!subjectId.matches("[A-Za-z0-9._:@-]+"))throw invalid();
    }
    private static boolean blank(String value){return value==null||value.isBlank();}
    private static ClientRequestException invalid(){return new ClientRequestException(
            "INVALID_IDENTITY_ASSIGNMENT","员工账号或授权信息不完整");}
}
