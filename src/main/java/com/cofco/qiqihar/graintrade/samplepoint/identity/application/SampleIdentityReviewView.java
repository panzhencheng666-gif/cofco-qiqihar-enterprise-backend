package com.cofco.qiqihar.graintrade.samplepoint.identity.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class SampleIdentityReviewView {
    private SampleIdentityReviewView() {}

    public record ReviewItem(
            UUID draftId,
            int version,
            String domainCode,
            String productCode,
            String sampleName,
            String sampleContact,
            String regionCode,
            BigDecimal longitude,
            BigDecimal latitude,
            String surveyPeriod,
            String reasonCode,
            String reasonMessage,
            String createdBy,
            Instant createdAt,
            List<CandidateView> candidates) {
        public ReviewItem {
            candidates = List.copyOf(candidates);
        }
    }

    public record CandidateView(
            UUID samplePointId,
            String canonicalName,
            String sampleContact,
            String regionCode,
            BigDecimal longitude,
            BigDecimal latitude,
            int approvedRecordCount,
            LocalDate effectiveFrom) {}

    public record DecisionView(
            UUID draftId,
            String decision,
            UUID targetSamplePointId,
            String reason,
            String decidedBy,
            Instant decidedAt,
            String stateCode,
            String canonicalRecordId,
            int version,
            boolean privilegedSelfReview) {}
}
