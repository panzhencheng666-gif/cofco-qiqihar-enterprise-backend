package com.cofco.qiqihar.graintrade.identity.application;

import com.cofco.qiqihar.graintrade.shared.application.AccessDeniedException;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.application.ConflictException;
import com.cofco.qiqihar.graintrade.shared.audit.application.BusinessAuditRecorder;
import com.cofco.qiqihar.graintrade.shared.security.application.AccessControl;
import com.cofco.qiqihar.graintrade.shared.security.application.SecurityPrincipalRepository;
import com.cofco.qiqihar.graintrade.shared.security.domain.SecurityPrincipal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class RegionResponsibilityService {
    private final RegionResponsibilityRepository repository;
    private final IdentityGovernanceService identities;
    private final SecurityPrincipalRepository principals;
    private final AccessControl access;
    private final BusinessAuditRecorder audit;
    private final ObjectMapper json;
    private final Clock clock;
    public RegionResponsibilityService(RegionResponsibilityRepository repository,IdentityGovernanceService identities,
            SecurityPrincipalRepository principals,AccessControl access,BusinessAuditRecorder audit,ObjectMapper json,Clock clock){
        this.repository=repository;this.identities=identities;this.principals=principals;
        this.access=access;this.audit=audit;this.json=json;this.clock=clock;
    }
    @Transactional(readOnly=true)
    public RegionResponsibility.Preview current(String subject){
        var reader=access.require("IDENTITY_READ",null);
        identities.employee(subject);
        List<String> owned=repository.ownedRegions(subject);
        if(!reader.regionCodes().containsAll(owned))throw new AccessDeniedException("ACCESS_REGION_DENIED","负责地区不在允许读取的范围内");
        var employee=identities.employee(subject);
        return new RegionResponsibility.Preview(subject,owned,repository.regions(owned),
            repository.samples(owned,owned,subject,employee.displayName()),null);
    }
    @Transactional(readOnly=true, isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public RegionResponsibility.Preview preview(String subject,List<String> selected){
        return calculate(subject,normalize(selected));
    }
    @Transactional
    public RegionResponsibility.Preview save(String subject,List<String> selected,String token,String reason){
        if(token==null||token.length()!=64||reason==null||reason.isBlank()||reason.length()>500)throw invalid();
        access.require("IDENTITY_ADMIN",null);
        repository.lockChange();
        var before=calculate(subject,normalize(selected));
        if(!MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),before.previewToken().getBytes(StandardCharsets.UTF_8)))
            throw new ConflictException("REGION_RESPONSIBILITY_CONFLICT","地区或样本责任已变化，请重新预览后保存");
        var actor=actor();
        var employee=identities.employee(subject);
        var affected=before.regions().stream().map(RegionResponsibility.Region::regionCode).toList();
        repository.save(subject,before.regionCodes(),affected,actor.subjectId(),reason.strip());
        var detail=new LinkedHashMap<String,Object>();
        detail.put("regionCodes",affected);detail.put("reason",reason.strip());
        detail.put("previousRegions",before.regions());detail.put("selectedRegionCodes",before.regionCodes());
        detail.put("sampleCount",before.samples().size());
        audit.record(actor,employee.workUnitCode(),"SECURITY_USER",subject,"REGION_RESPONSIBILITY_CHANGED",clock.instant(),json.writeValueAsString(detail));
        for(var point:before.samples()){
            if(Objects.equals(point.previousSubjectId(),point.nextSubjectId()))continue;
            var sampleDetail=new LinkedHashMap<String,Object>();
            sampleDetail.put("regionCode",point.regionCode());sampleDetail.put("previousMaintainerSubjectId",point.previousSubjectId());
            sampleDetail.put("maintainerSubjectId",point.nextSubjectId());sampleDetail.put("maintainerChangeReason",reason.strip());
            audit.record(actor,employee.workUnitCode(),"FORMAL_SAMPLE_POINT",point.id().toString(),"FORMAL_SAMPLE_MAINTAINER_REASSIGNED",clock.instant(),json.writeValueAsString(sampleDetail));
        }
        return current(subject);
    }
    private RegionResponsibility.Preview calculate(String subject,List<String> selected){
        SecurityPrincipal actor=actor();
        var employee=identities.employee(subject);
        if(!actor.roleCodes().contains("SYSTEM_ADMIN") && !actor.workUnitCode().equals(employee.workUnitCode()))
            throw new AccessDeniedException("ACCESS_WORK_UNIT_DENIED","无权调整其他工作单位的责任");
        var target=principals.findEnabled(subject).orElseThrow(RegionResponsibilityService::invalid);
        if(!target.permits("BUSINESS_CREATE"))throw invalid();
        var available=identities.assignmentOptions(employee.workUnitCode()).regionCodes();
        var affected=new TreeSet<>(repository.ownedRegions(subject));affected.addAll(selected);
        if(!available.containsAll(selected)||!actor.regionCodes().containsAll(affected))throw new AccessDeniedException(
            "ACCESS_REGION_DENIED","负责地区不在允许分配的范围内");
        var regions=repository.regions(List.copyOf(affected));
        var samples=repository.samples(List.copyOf(affected),selected,subject,employee.displayName());
        // Include grants and versions as well as the full sample set: additions and authorization edits invalidate previews.
        String snapshot=json.writeValueAsString(Arrays.asList(actor.subjectId(),actor.workUnitCode(),new TreeSet<>(actor.roleCodes()),new TreeSet<>(actor.permissionCodes()),new TreeSet<>(actor.regionCodes()),
            employee,target.workUnitCode(),new TreeSet<>(target.permissionCodes()),new TreeSet<>(target.regionCodes()),selected,regions,samples));
        return new RegionResponsibility.Preview(subject,selected,regions,samples,sha256(snapshot));
    }
    private SecurityPrincipal actor(){
        String subject=access.require("IDENTITY_ADMIN",null).subjectId();
        var actor=principals.findEnabled(subject).orElseThrow(RegionResponsibilityService::invalid);
        if(!actor.permits("IDENTITY_ADMIN")||!actor.permits("FORMAL_SAMPLE_MANAGE"))throw new AccessDeniedException(
            "ACCESS_PERMISSION_DENIED","无权调整地区和样本责任");
        return actor;
    }
    private static List<String> normalize(List<String> codes){
        if(codes==null||codes.size()>1000||codes.stream().anyMatch(code->code==null||code.isBlank())
                ||new HashSet<>(codes).size()!=codes.size())throw invalid();
        return codes.stream().sorted().toList();
    }
    private static String sha256(String value){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private static ClientRequestException invalid(){return new ClientRequestException("INVALID_REGION_RESPONSIBILITY","请核对有效员工、负责地区和调整原因");}
}
