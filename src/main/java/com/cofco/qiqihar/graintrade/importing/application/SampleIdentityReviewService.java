package com.cofco.qiqihar.graintrade.importing.application;

import static com.cofco.qiqihar.graintrade.samplepoint.identity.application.SampleIdentityReviewEvents.AGGREGATE_TYPE;
import static com.cofco.qiqihar.graintrade.samplepoint.identity.application.SampleIdentityReviewEvents.CONFIRM_DISTINCT;
import static com.cofco.qiqihar.graintrade.samplepoint.identity.application.SampleIdentityReviewEvents.LINK_EXISTING;
import static com.cofco.qiqihar.graintrade.samplepoint.identity.application.SampleIdentityReviewEvents.RETURN_FOR_CORRECTION;
import static com.cofco.qiqihar.graintrade.samplepoint.identity.application.SampleIdentityReviewEvents.SUBMITTED;

import com.cofco.qiqihar.graintrade.importing.domain.ImportDraft;
import com.cofco.qiqihar.graintrade.samplepoint.identity.application.SampleIdentityAssessment;
import com.cofco.qiqihar.graintrade.samplepoint.identity.application.SampleIdentityAssessment.Candidate;
import com.cofco.qiqihar.graintrade.samplepoint.identity.application.SampleIdentityAssessment.SubjectInput;
import com.cofco.qiqihar.graintrade.samplepoint.identity.application.SampleIdentityReviewView.CandidateView;
import com.cofco.qiqihar.graintrade.samplepoint.identity.application.SampleIdentityReviewView.DecisionView;
import com.cofco.qiqihar.graintrade.samplepoint.identity.application.SampleIdentityReviewView.ReviewItem;
import com.cofco.qiqihar.graintrade.samplepoint.identity.infrastructure.JdbcSampleIdentityGovernanceRepository;
import com.cofco.qiqihar.graintrade.samplepoint.identity.infrastructure.JdbcSampleIdentityReviewRepository;
import com.cofco.qiqihar.graintrade.samplepoint.identity.infrastructure.JdbcSampleIdentityReviewRepository.DecisionSnapshot;
import com.cofco.qiqihar.graintrade.samplepoint.identity.infrastructure.JdbcSampleIdentityReviewRepository.SubmissionSnapshot;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.application.ConflictException;
import com.cofco.qiqihar.graintrade.shared.application.ResourceNotFoundException;
import com.cofco.qiqihar.graintrade.shared.audit.application.BusinessAuditRecorder;
import com.cofco.qiqihar.graintrade.shared.security.application.AccessControl;
import com.cofco.qiqihar.graintrade.shared.security.application.SeparationOfDutiesPolicy;
import com.cofco.qiqihar.graintrade.shared.security.domain.SecurityPrincipal;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class SampleIdentityReviewService {
    private final ImportDraftRepository drafts;
    private final ImportDraftPromotionService promotion;
    private final JdbcSampleIdentityGovernanceRepository identities;
    private final JdbcSampleIdentityReviewRepository reviews;
    private final AccessControl access;
    private final SeparationOfDutiesPolicy separationOfDuties;
    private final BusinessAuditRecorder audit;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public SampleIdentityReviewService(
            ImportDraftRepository drafts, ImportDraftPromotionService promotion,
            JdbcSampleIdentityGovernanceRepository identities,
            JdbcSampleIdentityReviewRepository reviews, AccessControl access,
            SeparationOfDutiesPolicy separationOfDuties, BusinessAuditRecorder audit,
            ObjectMapper objectMapper, Clock clock) {
        this.drafts = drafts;
        this.promotion = promotion;
        this.identities = identities;
        this.reviews = reviews;
        this.access = access;
        this.separationOfDuties = separationOfDuties;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<ReviewItem> pendingReviews() {
        SecurityPrincipal principal = access.require("BUSINESS_APPROVE", null);
        List<ReviewItem> result = new ArrayList<>();
        for (ImportDraft draft : drafts.findPendingIdentityReviews()) {
            if (!principal.includesRegion(draft.regionCode())) continue;
            SubmissionSnapshot submission = reviews.submission(draft.id()).orElse(null);
            if (submission == null || !principal.workUnitCode().equals(submission.workUnitCode())) continue;
            result.add(reviewItem(draft, submission));
        }
        return List.copyOf(result);
    }

    @Transactional
    public DecisionView reviewDraft(
            UUID draftId, String decisionValue, UUID targetSamplePointId,
            int expectedVersion, String reason) {
        ImportDraft draft = drafts.findByIdForUpdate(draftId).orElseThrow(() ->
                new ResourceNotFoundException("SAMPLE_IDENTITY_REVIEW_NOT_FOUND", "身份待核验记录不存在"));
        SecurityPrincipal principal = access.require("BUSINESS_APPROVE", draft.regionCode());
        SubmissionSnapshot submission = reviews.submission(draftId).orElseThrow(() ->
                new ResourceNotFoundException("SAMPLE_IDENTITY_REVIEW_NOT_FOUND", "身份待核验记录不存在"));
        if (!principal.workUnitCode().equals(submission.workUnitCode())) {
            throw new ConflictException("SAMPLE_IDENTITY_WORK_UNIT_MISMATCH",
                    "身份待核验记录不属于当前单位");
        }
        Decision decision = decision(decisionValue);
        validateRequest(decision, targetSamplePointId, expectedVersion, reason);
        var existing = reviews.decision(draftId);
        if (existing.isPresent()) {
            if (existing.get().decision().equals(decision.name())
                    && java.util.Objects.equals(existing.get().targetSamplePointId(), targetSamplePointId)) {
                return decisionView(draft, existing.get());
            }
            throw new ConflictException("SAMPLE_IDENTITY_ALREADY_REVIEWED", "该身份记录已完成核验");
        }
        if (draft.version() != expectedVersion) {
            throw new ConflictException("SAMPLE_IDENTITY_REVIEW_STALE", "身份记录已发生变化，请刷新后重试");
        }
        if (!"DRAFT".equals(draft.stateCode())) {
            throw new ConflictException("SAMPLE_IDENTITY_ALREADY_REVIEWED", "该身份记录已完成核验");
        }
        boolean mayReview = decision == Decision.RETURN_FOR_CORRECTION
                ? separationOfDuties.canReturn(AGGREGATE_TYPE, draftId.toString(), SUBMITTED, principal)
                : separationOfDuties.canApprove(AGGREGATE_TYPE, draftId.toString(), SUBMITTED, principal);
        if (!mayReview) {
            throw new ConflictException("SAMPLE_IDENTITY_SELF_REVIEW_FORBIDDEN",
                    "身份判定必须由另一名审核人完成；平台唯一所有者可按特权规则自审");
        }
        SubjectInput input = input(draft);
        SampleIdentityAssessment assessment = identities.assess(input);
        if (decision == Decision.LINK_EXISTING) {
            Candidate target = assessment.candidates().stream()
                    .filter(candidate -> candidate.samplePointId().equals(targetSamplePointId))
                    .findFirst().orElseThrow(() -> new ConflictException(
                            "SAMPLE_IDENTITY_TARGET_INVALID", "所选规范样本点已失效或不再属于当前候选范围"));
            requireCompatibleTarget(draft, input, target);
        }
        boolean privilegedSelfReview = principal.subjectId().equals(submission.submittedBy());
        Instant now = clock.instant();
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("draftId", draftId);
        detail.put("decision", decision.name());
        if (targetSamplePointId != null) detail.put("targetSamplePointId", targetSamplePointId);
        detail.put("reason", reason.trim());
        detail.put("submittedBy", submission.submittedBy());
        detail.put("privilegedSelfReview", privilegedSelfReview);
        List<UUID> coordinateSharedSamplePointIds = decision == Decision.CONFIRM_DISTINCT
                ? assessment.candidates().stream()
                        .filter(candidate -> candidate.regionCode().equals(input.regionCode())
                                && candidate.longitude().compareTo(input.longitude()) == 0
                                && candidate.latitude().compareTo(input.latitude()) == 0)
                        .map(Candidate::samplePointId).distinct().sorted().toList()
                : List.of();
        boolean coordinateShared = !coordinateSharedSamplePointIds.isEmpty();
        detail.put("coordinateShared", coordinateShared);
        if (coordinateShared) {
            detail.put("coordinateSharedSamplePointIds", coordinateSharedSamplePointIds);
            detail.put("coordinateSharedLongitude", input.longitude());
            detail.put("coordinateSharedLatitude", input.latitude());
        }
        audit.record(principal, submission.workUnitCode(), AGGREGATE_TYPE, draftId.toString(),
                action(decision), now, json(detail));
        if (decision == Decision.RETURN_FOR_CORRECTION) {
            return new DecisionView(draftId, decision.name(), null, reason.trim(),
                    principal.subjectId(), now, draft.stateCode(), null, draft.version(),
                    privilegedSelfReview);
        }
        ImportDraft promoted = promotion.submitAfterIdentityReview(draftId);
        Map<String, Object> recordDetail = new LinkedHashMap<>(detail);
        recordDetail.put("canonicalRecordId", promoted.canonicalRecordId());
        audit.record(principal, submission.workUnitCode(), recordAggregateType(draft.domainCode()),
                promoted.canonicalRecordId(), action(decision), now, json(recordDetail));
        return new DecisionView(draftId, decision.name(), targetSamplePointId, reason.trim(),
                principal.subjectId(), now, promoted.stateCode(), promoted.canonicalRecordId(),
                promoted.version(), privilegedSelfReview);
    }

    private ReviewItem reviewItem(ImportDraft draft, SubmissionSnapshot submission) {
        SubjectInput input = input(draft);
        SampleIdentityAssessment assessment = identities.assess(input);
        List<CandidateView> candidates = assessment.candidates().stream()
                .map(SampleIdentityReviewService::candidateView).toList();
        return new ReviewItem(draft.id(), draft.version(), draft.domainCode(), draft.productCode(),
                draft.sampleName(), input.sampleContact(), draft.regionCode(), input.longitude(),
                input.latitude(), draft.surveyPeriod(),
                value(submission.reasonCode(), assessment.reasonCode()),
                value(submission.reasonMessage(), assessment.reasonMessage()),
                draft.createdBy(), draft.createdAt(), candidates);
    }

    private static CandidateView candidateView(Candidate candidate) {
        return new CandidateView(candidate.samplePointId(), candidate.canonicalName(),
                candidate.sampleContact(), candidate.regionCode(), candidate.longitude(),
                candidate.latitude(), candidate.approvedRecordCount(), candidate.effectiveFrom());
    }

    private DecisionView decisionView(ImportDraft draft, DecisionSnapshot decision) {
        return new DecisionView(draft.id(), decision.decision(), decision.targetSamplePointId(),
                decision.reason(), decision.decidedBy(), decision.decidedAt(), draft.stateCode(),
                draft.canonicalRecordId(), draft.version(), decision.privilegedSelfReview());
    }

    private static void requireCompatibleTarget(
            ImportDraft draft, SubjectInput input, Candidate target) {
        if (!target.regionCode().equals(draft.regionCode())
                || target.longitude().compareTo(input.longitude()) != 0
                || target.latitude().compareTo(input.latitude()) != 0
                || target.effectiveFrom().isAfter(effectiveOn(draft))) {
            throw new ConflictException("SAMPLE_IDENTITY_TARGET_INVALID",
                    "所选规范样本点的地区、坐标或生效时间与本行不一致");
        }
    }

    private static LocalDate effectiveOn(ImportDraft draft) {
        int year = Integer.parseInt(draft.values().get("surveyYear"));
        String monthValue = draft.values().get("surveyMonth");
        int month = monthValue == null || monthValue.isBlank() ? 1 : Integer.parseInt(monthValue);
        return LocalDate.of(year, month, 1);
    }

    private static SubjectInput input(ImportDraft draft) {
        String prefix = switch (draft.domainCode()) {
            case "PRODUCTION" -> "PROD";
            case "MARKET" -> "MKT";
            default -> throw new IllegalArgumentException("Unsupported sample identity review domain");
        };
        return new SubjectInput(draft.domainCode(), draft.sampleName(),
                draft.values().get(prefix + "_SAMPLE_CONTACT"), draft.regionCode(),
                decimal(draft.values().get(prefix + "_SAMPLE_LONGITUDE")),
                decimal(draft.values().get(prefix + "_SAMPLE_LATITUDE")));
    }

    private static BigDecimal decimal(String value) {
        return value == null || value.isBlank() ? null : new BigDecimal(value.trim());
    }

    private static Decision decision(String value) {
        try {
            return Decision.valueOf(value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new ClientRequestException("INVALID_SAMPLE_IDENTITY_DECISION",
                    "身份判定必须为关联已有、确认不同或退回修正");
        }
    }

    private static void validateRequest(
            Decision decision, UUID targetSamplePointId, int expectedVersion, String reason) {
        if ((decision == Decision.LINK_EXISTING) != (targetSamplePointId != null)
                || expectedVersion < 0 || reason == null || reason.isBlank()
                || reason.trim().length() > 500) {
            throw new ClientRequestException("INVALID_SAMPLE_IDENTITY_DECISION",
                    "请选择有效判定，并填写明确的核验依据");
        }
    }

    private static String action(Decision decision) {
        return switch (decision) {
            case LINK_EXISTING -> LINK_EXISTING;
            case CONFIRM_DISTINCT -> CONFIRM_DISTINCT;
            case RETURN_FOR_CORRECTION -> RETURN_FOR_CORRECTION;
        };
    }

    private static String recordAggregateType(String domainCode) {
        return switch (domainCode) {
            case "PRODUCTION" -> "PRODUCTION_RECORD";
            case "MARKET" -> "MARKET_RECORD";
            default -> throw new IllegalArgumentException("Unsupported sample identity review domain");
        };
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Sample identity review detail cannot be serialized", exception);
        }
    }

    private static String value(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }

    public enum Decision { LINK_EXISTING, CONFIRM_DISTINCT, RETURN_FOR_CORRECTION }
}
