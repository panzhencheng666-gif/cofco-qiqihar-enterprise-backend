package com.cofco.qiqihar.graintrade.shared.security.application;

import com.cofco.qiqihar.graintrade.shared.application.AccessDeniedException;
import com.cofco.qiqihar.graintrade.shared.audit.application.BusinessAuditActorReader;
import com.cofco.qiqihar.graintrade.shared.security.domain.SecurityPrincipal;
import org.springframework.stereotype.Service;

@Service
public class SeparationOfDutiesPolicy {
    private final BusinessAuditActorReader auditActors;

    public SeparationOfDutiesPolicy(BusinessAuditActorReader auditActors) {
        this.auditActors = auditActors;
    }

    public void requireIndependentApprover(
            String aggregateType, String aggregateId, String submittedAction, SecurityPrincipal approver) {
        requireIndependentReviewer(aggregateType, aggregateId, submittedAction, approver,
                "SELF_APPROVAL_FORBIDDEN", "The submitting employee cannot approve the same record");
    }

    public void requireIndependentReturner(
            String aggregateType, String aggregateId, String submittedAction, SecurityPrincipal reviewer) {
        requireIndependentReviewer(aggregateType, aggregateId, submittedAction, reviewer,
                "SELF_RETURN_FORBIDDEN", "The submitting employee cannot return the same record");
    }

    public boolean isIndependentReviewer(
            String aggregateType, String aggregateId, String submittedAction, SecurityPrincipal reviewer) {
        return auditActors.latestActor(aggregateType, aggregateId, submittedAction)
                .filter(actor -> !actor.equals(reviewer.subjectId())).isPresent();
    }

    private void requireIndependentReviewer(String aggregateType, String aggregateId, String submittedAction,
            SecurityPrincipal reviewer, String errorCode, String message) {
        String submitter = auditActors.latestActor(aggregateType, aggregateId, submittedAction)
                .orElseThrow(() -> new AccessDeniedException(
                        "SUBMISSION_PROVENANCE_REQUIRED", "Submission provenance is required for approval"));
        if (submitter.equals(reviewer.subjectId())) {
            throw new AccessDeniedException(errorCode, message);
        }
    }
}
