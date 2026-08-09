package com.cofco.qiqihar.graintrade.identity.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AccessReviewCampaign(
        UUID reviewId,
        String name,
        String workUnitCode,
        String statusCode,
        Instant dueAt,
        String createdBy,
        Instant createdAt,
        List<Item> items) {
    public record Item(
            String subjectId,
            String grantType,
            String grantKey,
            String decisionCode,
            String decidedBy,
            Instant decidedAt,
            String reason) {}
}
