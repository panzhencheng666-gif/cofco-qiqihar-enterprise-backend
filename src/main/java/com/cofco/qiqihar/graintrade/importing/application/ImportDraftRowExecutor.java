package com.cofco.qiqihar.graintrade.importing.application;

import com.cofco.qiqihar.graintrade.importing.domain.ImportDraft;
import com.cofco.qiqihar.graintrade.shared.audit.application.BusinessAuditRecorder;
import com.cofco.qiqihar.graintrade.shared.security.domain.SecurityPrincipal;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ImportDraftRowExecutor {
    private final ImportDraftRepository repository;
    private final ImportDraftPromotionService promotion;
    private final BusinessAuditRecorder audit;

    public ImportDraftRowExecutor(ImportDraftRepository repository, ImportDraftPromotionService promotion,
            BusinessAuditRecorder audit) {
        this.repository = repository;
        this.promotion = promotion;
        this.audit = audit;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SubmissionResult createAndSubmit(ImportDraft draft, List<UUID> evidenceIds) {
        ImportDraft stored = repository.insert(draft);
        List<UUID> requested = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        int bound = repository.bindEvidence(stored.id(), requested, stored.createdAt());
        ImportDraft submitted = promotion.submit(stored.id());
        if (bound == requested.size()) return new SubmissionResult(submitted, null, null);
        return new SubmissionResult(submitted, "IMPORT_PHOTO_ALREADY_USED",
                "部分照片已由其他样本点使用，本行数据已正常导入");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SubmissionResult createPendingIdentityReview(ImportDraft draft, List<UUID> evidenceIds,
            String warningCode, String warningMessage, SecurityPrincipal principal,
            String auditDetailJson) {
        ImportDraft stored = repository.insert(draft);
        List<UUID> requested = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        int bound = repository.bindEvidence(stored.id(), requested, stored.createdAt());
        String message = warningMessage;
        if (bound != requested.size()) {
            message = message + "；部分照片已由其他样本点使用，未重复绑定";
        }
        audit.record(principal, "SAMPLE_IDENTITY_REVIEW", stored.id().toString(),
                "SAMPLE_IDENTITY_REVIEW_SUBMITTED", stored.createdAt(), auditDetailJson);
        return new SubmissionResult(stored, warningCode, message);
    }

    public record SubmissionResult(ImportDraft draft, String warningCode, String warningMessage) {}
}
