package com.cofco.qiqihar.graintrade.risk.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RiskWorkbenchRepository {
    List<RiskAssessmentSummary> findAssessments(RiskAssessmentQuery query);
    Optional<RiskAssessmentDetail> findAssessment(UUID assessmentId);
    boolean assessmentExists(UUID assessmentId);
    Optional<RiskFeedback> createFeedback(
            UUID assessmentId,String conclusionCode,String reasonCode,String dispositionNote,
            String resolvedBySubject,Instant resolvedAt);
}
