package com.cofco.qiqihar.graintrade.identity.application;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface IdentityDeliveryOutboxRepository {
    Optional<ClaimedDelivery> claimNext(Instant now,Duration lease);
    void markDelivered(UUID eventId,UUID invitationId,Instant deliveredAt);
    void markFailed(UUID eventId,UUID invitationId,Instant retryAt,String errorCode,String safeMessage);
    int expireInvitations(Instant now);

    record ClaimedDelivery(
            UUID eventId,UUID invitationId,String subjectId,String encryptedPayload,
            Instant expiresAt,int attemptCount) {}
}
