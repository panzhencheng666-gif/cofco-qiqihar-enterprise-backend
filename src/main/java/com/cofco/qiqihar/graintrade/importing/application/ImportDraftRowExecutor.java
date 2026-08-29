package com.cofco.qiqihar.graintrade.importing.application;

import com.cofco.qiqihar.graintrade.importing.domain.ImportDraft;
import com.cofco.qiqihar.graintrade.samplepoint.identity.application.SampleIdentityAssessment;
import com.cofco.qiqihar.graintrade.shared.audit.application.BusinessAuditRecorder;
import com.cofco.qiqihar.graintrade.shared.security.domain.SecurityPrincipal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class ImportDraftRowExecutor {
    private final ImportDraftRepository repository;
    private final ImportDraftPromotionService promotion;
    private final BusinessPeriodRecordGuard periodRecords;
    private final BusinessAuditRecorder audit;
    private final ObjectMapper objectMapper;

    public ImportDraftRowExecutor(ImportDraftRepository repository, ImportDraftPromotionService promotion,
            BusinessPeriodRecordGuard periodRecords, BusinessAuditRecorder audit,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.promotion = promotion;
        this.periodRecords = periodRecords;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SubmissionResult createAndSubmit(ImportDraft draft, List<UUID> evidenceIds,
            SecurityPrincipal principal, SampleIdentityAssessment identityDecision) {
        periodRecords.lockAndRequireAvailable(draft);
        ImportDraft stored = repository.insert(draft);
        List<UUID> requested = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        int bound = repository.bindEvidence(stored.id(), requested, stored.createdAt());
        ImportDraft submitted = promotion.submit(stored.id());
        recordIdentityDecision(submitted, principal, identityDecision);
        if (bound == requested.size()) return new SubmissionResult(submitted, null, null);
        return new SubmissionResult(submitted, "IMPORT_PHOTO_ALREADY_USED",
                "部分照片已由其他样本点使用，本行数据已正常导入");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SubmissionResult createPendingIdentityReview(ImportDraft draft, List<UUID> evidenceIds,
            String warningCode, String warningMessage, SecurityPrincipal principal,
            String auditDetailJson) {
        periodRecords.lockAndRequireAvailable(draft);
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

    private void recordIdentityDecision(ImportDraft promoted, SecurityPrincipal principal,
            SampleIdentityAssessment decision) {
        if (decision == null || decision.outcome() == SampleIdentityAssessment.Outcome.REVIEW_REQUIRED) return;
        String actionCode = decision.outcome() == SampleIdentityAssessment.Outcome.MATCHED
                ? "SAMPLE_IDENTITY_LINK_EXISTING"
                : "SAMPLE_IDENTITY_CONFIRM_DISTINCT";
        String aggregateType = switch (promoted.domainCode()) {
            case "PRODUCTION" -> "PRODUCTION_RECORD";
            case "MARKET" -> "MARKET_RECORD";
            case "LOGISTICS" -> "LOGISTICS_RECORD";
            default -> null;
        };
        if (aggregateType == null) return;
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("draftId", promoted.id());
        detail.put("decision", decision.outcome().name());
        detail.put("reasonCode", decision.reasonCode());
        detail.put("reasonMessage", decision.reasonMessage());
        if (decision.matchedSamplePointId() != null) {
            detail.put("targetSamplePointId", decision.matchedSamplePointId());
        }
        audit.record(principal, aggregateType, promoted.canonicalRecordId(), actionCode,
                promoted.updatedAt(), json(detail));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Import identity decision cannot be serialized", exception);
        }
    }

    public record SubmissionResult(ImportDraft draft, String warningCode, String warningMessage) {}
}
