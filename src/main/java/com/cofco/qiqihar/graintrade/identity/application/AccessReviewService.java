package com.cofco.qiqihar.graintrade.identity.application;

import com.cofco.qiqihar.graintrade.shared.application.AccessDeniedException;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.application.ConflictException;
import com.cofco.qiqihar.graintrade.shared.application.ResourceNotFoundException;
import com.cofco.qiqihar.graintrade.shared.audit.application.BusinessAuditRecorder;
import com.cofco.qiqihar.graintrade.shared.security.application.AccessControl;
import com.cofco.qiqihar.graintrade.shared.security.domain.SecurityPrincipal;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccessReviewService {
    private static final Set<String> GRANT_TYPES=Set.of("ROLE","REGION","POSITION");
    private static final Set<String> DECISIONS=Set.of("RETAIN","REVOKE");
    private final AccessReviewRepository repository;
    private final AccessControl access;
    private final BusinessAuditRecorder audit;
    private final Clock clock;

    public AccessReviewService(AccessReviewRepository repository, AccessControl access,
            BusinessAuditRecorder audit, Clock clock) {
        this.repository=repository;this.access=access;this.audit=audit;this.clock=clock;
    }

    @Transactional
    public AccessReviewCampaign create(String name,String workUnitCode,Instant dueAt) {
        SecurityPrincipal actor=access.require("ACCESS_REVIEW",null);
        Instant now=clock.instant();
        if(blank(name)||name.codePointCount(0,name.length())>160||blank(workUnitCode)
                ||dueAt==null||!dueAt.isAfter(now)||!repository.workUnitExists(workUnitCode)
                ||!mayReview(actor,workUnitCode))throw invalid();
        UUID id=UUID.randomUUID();
        AccessReviewCampaign campaign=repository.create(id,name.strip(),workUnitCode,dueAt,actor.subjectId(),now);
        audit.record(actor,workUnitCode,"ACCESS_REVIEW",id.toString(),"ACCESS_REVIEW_OPENED",now,
                "{\"workUnitCode\":\""+workUnitCode+"\"}");
        return campaign;
    }

    @Transactional(readOnly=true)
    public List<AccessReviewCampaign> reviews(String workUnitCode) {
        SecurityPrincipal actor=access.require("ACCESS_REVIEW",null);
        if(blank(workUnitCode)||!repository.workUnitExists(workUnitCode)
                ||!mayReview(actor,workUnitCode))throw invalid();
        return repository.findByWorkUnit(workUnitCode);
    }

    @Transactional(readOnly=true)
    public AccessReviewCampaign review(UUID reviewId) {
        SecurityPrincipal actor=access.require("ACCESS_REVIEW",null);
        AccessReviewCampaign campaign=required(reviewId);
        requireReviewScope(actor,campaign.workUnitCode());
        return campaign;
    }

    @Transactional
    public AccessReviewCampaign decide(UUID reviewId,List<AccessReviewDecision> decisions) {
        SecurityPrincipal actor=access.require("ACCESS_REVIEW",null);
        AccessReviewCampaign campaign=required(reviewId);
        requireReviewScope(actor,campaign.workUnitCode());
        if(!"OPEN".equals(campaign.statusCode())||decisions==null||decisions.isEmpty()
                ||decisions.size()>500||hasDuplicates(decisions)||decisions.stream().anyMatch(this::invalid))throw invalid();
        Instant now=clock.instant();
        if(!repository.decide(reviewId,decisions,actor.subjectId(),now))throw new ConflictException(
                "ACCESS_REVIEW_CONFLICT","Access review has already changed");
        AccessReviewCampaign updated=required(reviewId);
        audit.record(actor,campaign.workUnitCode(),"ACCESS_REVIEW",reviewId.toString(),"ACCESS_REVIEW_DECIDED",now,
                "{\"decisionCount\":"+decisions.size()+",\"status\":\""+updated.statusCode()+"\"}");
        return updated;
    }

    private AccessReviewCampaign required(UUID id) {
        if(id==null)throw invalid();
        return repository.find(id).orElseThrow(() -> new ResourceNotFoundException(
                "ACCESS_REVIEW_NOT_FOUND","Access review does not exist"));
    }
    private boolean invalid(AccessReviewDecision value) {
        return value==null||blank(value.subjectId())||!GRANT_TYPES.contains(value.grantType())
                ||blank(value.grantKey())||!DECISIONS.contains(value.decisionCode())
                ||blank(value.reason())||value.reason().codePointCount(0,value.reason().length())>500;
    }
    private static boolean hasDuplicates(List<AccessReviewDecision> values) {
        Set<String> keys=new HashSet<>();
        return values.stream().anyMatch(value -> value!=null&&!keys.add(
                value.subjectId()+"\u0000"+value.grantType()+"\u0000"+value.grantKey()));
    }
    private static boolean mayReview(SecurityPrincipal actor,String workUnitCode) {
        return actor.roleCodes().contains("SYSTEM_ADMIN")||actor.workUnitCode().equals(workUnitCode);
    }
    private static void requireReviewScope(SecurityPrincipal actor,String workUnitCode) {
        if(!mayReview(actor,workUnitCode))throw new AccessDeniedException(
                "ACCESS_WORK_UNIT_DENIED","Access review is outside the assigned work unit");
    }
    private static boolean blank(String value){return value==null||value.isBlank();}
    private static ClientRequestException invalid(){return new ClientRequestException(
            "INVALID_ACCESS_REVIEW_REQUEST","Access review request is invalid");}
}
