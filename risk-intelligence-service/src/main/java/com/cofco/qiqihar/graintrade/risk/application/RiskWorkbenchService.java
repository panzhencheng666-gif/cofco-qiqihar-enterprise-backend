package com.cofco.qiqihar.graintrade.risk.application;

import com.cofco.qiqihar.riskintelligence.security.RiskRegionScope;

import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.application.ConflictException;
import com.cofco.qiqihar.graintrade.shared.application.ResourceNotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RiskWorkbenchService {
    private static final Set<String> DOMAINS=Set.of(
            "INVENTORY","MARKET","SUPPLY","LOGISTICS","QUALITY","OPERATIONS","DATA_PIPELINE");
    private static final Set<String> LEVELS=Set.of("NONE","LOW","MEDIUM","HIGH","CRITICAL","UNAVAILABLE");
    private static final Set<String> REVIEW_STATUSES=Set.of("OPEN","REVIEWED","ALL");
    private static final Set<String> CONCLUSIONS=Set.of(
            "CONFIRMED","FALSE_POSITIVE","MISSED_RISK","INSUFFICIENT_EVIDENCE");

    private final RiskWorkbenchRepository repository;
    private final Clock clock;

    public RiskWorkbenchService(RiskWorkbenchRepository repository,Clock clock) {
        this.repository=repository;
        this.clock=clock;
    }

    @Transactional(readOnly=true)
    public List<RiskAssessmentSummary> assessments(
            String domainCode,String riskLevel,String reviewStatus,String search,int limit, RiskRegionScope scope) {
        RiskAssessmentQuery query=query(domainCode,riskLevel,reviewStatus,search,limit);
        return repository.findAssessments(query, scope);
    }

    @Transactional(readOnly=true)
    public RiskAssessmentDetail assessment(UUID assessmentId, RiskRegionScope scope) {
        if(assessmentId==null)throw invalidQuery();
        return repository.findAssessment(assessmentId, scope).orElseThrow(() -> new ResourceNotFoundException(
                "RISK_ASSESSMENT_NOT_FOUND","风险研判记录不存在或已失效"));
    }

    @Transactional
    public RiskFeedback submitFeedback(
            UUID assessmentId,String conclusionCode,String reasonCode,String dispositionNote,
            String actorSubject, RiskRegionScope scope) {
        if(assessmentId==null||!CONCLUSIONS.contains(conclusionCode)||blank(reasonCode)
                ||!reasonCode.matches("[A-Z0-9_]{2,80}")||blank(dispositionNote)
                ||dispositionNote.codePointCount(0,dispositionNote.length())>2000) {
            throw new ClientRequestException("INVALID_RISK_FEEDBACK","复核结论、原因和处置说明不完整");
        }
        if(!repository.assessmentExists(assessmentId, scope))throw new ResourceNotFoundException(
                "RISK_ASSESSMENT_NOT_FOUND","风险研判记录不存在或已失效");
        Instant now=clock.instant();
        RiskFeedback feedback=repository.createFeedback(
                assessmentId,conclusionCode,reasonCode,dispositionNote.strip(),actorSubject,now,scope)
                .orElseThrow(() -> {
                    if (!repository.assessmentExists(assessmentId, scope)) {
                        return new ResourceNotFoundException(
                                "RISK_ASSESSMENT_NOT_FOUND","风险研判记录不存在或已失效");
                    }
                    return new ConflictException(
                            "RISK_FEEDBACK_ALREADY_EXISTS","该风险事件已经完成复核，请刷新后查看");
                });
        return feedback;
    }

    private static RiskAssessmentQuery query(
            String domainCode,String riskLevel,String reviewStatus,String search,int limit) {
        String domain=normalize(domainCode);
        String level=normalize(riskLevel);
        String status=normalize(reviewStatus);
        String term=search==null?"":search.strip();
        if((!domain.isEmpty()&&!DOMAINS.contains(domain))
                ||(!level.isEmpty()&&!LEVELS.contains(level))
                ||(!status.isEmpty()&&!REVIEW_STATUSES.contains(status))
                ||term.codePointCount(0,term.length())>160||limit<1||limit>200)throw invalidQuery();
        return new RiskAssessmentQuery(domain,level,status.isEmpty()?"OPEN":status,term,limit);
    }

    private static String normalize(String value){return value==null?"":value.strip().toUpperCase();}
    private static boolean blank(String value){return value==null||value.isBlank();}
    private static ClientRequestException invalidQuery(){return new ClientRequestException(
            "INVALID_RISK_QUERY","风险事件查询条件无效");}
}
