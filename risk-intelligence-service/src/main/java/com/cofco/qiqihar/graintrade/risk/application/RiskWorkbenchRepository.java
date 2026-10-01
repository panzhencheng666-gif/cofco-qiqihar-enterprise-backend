package com.cofco.qiqihar.graintrade.risk.application;

import com.cofco.qiqihar.riskintelligence.security.RiskRegionScope;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RiskWorkbenchRepository {
    List<RiskAssessmentSummary> findAssessments(RiskAssessmentQuery query, RiskRegionScope scope);
    Optional<RiskAssessmentDetail> findAssessment(UUID assessmentId, RiskRegionScope scope);
    boolean assessmentExists(UUID assessmentId, RiskRegionScope scope);
    Optional<RiskFeedback> createFeedback(
            UUID assessmentId,String conclusionCode,String reasonCode,String dispositionNote,
            String resolvedBySubject,Instant resolvedAt, RiskRegionScope scope);
}
