package com.cofco.qiqihar.graintrade.identity.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccessReviewRepository {
    boolean workUnitExists(String code);
    AccessReviewCampaign create(UUID reviewId, String name, String workUnitCode,
            Instant dueAt, String actor, Instant now);
    Optional<AccessReviewCampaign> find(UUID reviewId);
    boolean decide(UUID reviewId, List<AccessReviewDecision> decisions, String actor, Instant now);
}
