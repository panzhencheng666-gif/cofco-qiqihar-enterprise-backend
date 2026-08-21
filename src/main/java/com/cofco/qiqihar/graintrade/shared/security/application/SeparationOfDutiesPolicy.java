package com.cofco.qiqihar.graintrade.shared.security.application;

import com.cofco.qiqihar.graintrade.shared.application.AccessDeniedException;
import com.cofco.qiqihar.graintrade.shared.audit.application.BusinessAuditActorReader;
import com.cofco.qiqihar.graintrade.shared.security.domain.SecurityPrincipal;
import org.springframework.stereotype.Service;

@Service
public class SeparationOfDutiesPolicy {
    private static final String ACCOUNT_OWNER_ROLE = "ACCOUNT_OWNER";
    private static final String SELF_APPROVAL_PERMISSION = "BUSINESS_SELF_APPROVE";
    private final BusinessAuditActorReader auditActors;

    public SeparationOfDutiesPolicy(BusinessAuditActorReader auditActors) {
        this.auditActors = auditActors;
    }

    public void requireIndependentApprover(
            String aggregateType, String aggregateId, String submittedAction, SecurityPrincipal approver) {
        String submitter = requireSubmitter(aggregateType, aggregateId, submittedAction);
        if (submitter.equals(approver.subjectId()) && !hasAccountOwnerSelfReviewPrivilege(approver)) {
            throw new AccessDeniedException(
                    "SELF_APPROVAL_FORBIDDEN", "提交人不能审核自己提交的记录");
        }
    }

    public void requireIndependentReturner(
            String aggregateType, String aggregateId, String submittedAction, SecurityPrincipal reviewer) {
        String submitter = requireSubmitter(aggregateType, aggregateId, submittedAction);
        if (submitter.equals(reviewer.subjectId()) && !hasAccountOwnerSelfReviewPrivilege(reviewer)) {
            throw new AccessDeniedException(
                    "SELF_RETURN_FORBIDDEN", "提交人不能驳回自己提交的记录");
        }
    }

    public boolean canApprove(
            String aggregateType, String aggregateId, String submittedAction, SecurityPrincipal reviewer) {
        return auditActors.latestActor(aggregateType, aggregateId, submittedAction)
                .map(actor -> !actor.equals(reviewer.subjectId())
                        || hasAccountOwnerSelfReviewPrivilege(reviewer))
                .orElse(false);
    }

    public boolean canReturn(
            String aggregateType, String aggregateId, String submittedAction, SecurityPrincipal reviewer) {
        return auditActors.latestActor(aggregateType, aggregateId, submittedAction)
                .map(actor -> !actor.equals(reviewer.subjectId())
                        || hasAccountOwnerSelfReviewPrivilege(reviewer))
                .orElse(false);
    }

    private String requireSubmitter(String aggregateType, String aggregateId, String submittedAction) {
        return auditActors.latestActor(aggregateType, aggregateId, submittedAction)
                .orElseThrow(() -> new AccessDeniedException(
                        "SUBMISSION_PROVENANCE_REQUIRED", "缺少提交来源，暂不能形成审核结论"));
    }

    private static boolean hasAccountOwnerSelfReviewPrivilege(SecurityPrincipal principal) {
        return principal.roleCodes().contains(ACCOUNT_OWNER_ROLE)
                && principal.permits(SELF_APPROVAL_PERMISSION);
    }
}
