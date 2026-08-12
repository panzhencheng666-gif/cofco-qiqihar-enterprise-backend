package com.cofco.qiqihar.graintrade.notification.application;

import com.cofco.qiqihar.graintrade.shared.security.application.AuthorizedReadScope;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface BusinessEventDeliveryRepository {
    void ensureCheckpoint(
            String consumerId,
            String instanceId,
            long initialSequence);

    boolean retireConsumer(
            String consumerId,
            String instanceId,
            long resumeSequence,
            ConsumerRetirementReason reason);

    int expireStaleConsumers();

    Optional<Instant> pollRetryAt(String consumerId);

    void recordPollSucceeded(String consumerId, String instanceId, long afterSequence, Instant now);

    Duration recordPollFailed(
            String consumerId,
            String instanceId,
            long afterSequence,
            String failureCode,
            String failureMessage,
            Instant now,
            Duration baseRetry,
            Duration maximumRetry);

    ClaimDecision claim(
            String consumerId,
            String instanceId,
            BusinessNotification event,
            Instant now,
            Instant leaseUntil);

    boolean markDelivered(DeliveryClaim claim, Instant deliveredAt);

    boolean markFailed(
            DeliveryClaim claim,
            String failureCode,
            String failureMessage,
            Instant failedAt,
            Instant nextRetryAt,
            boolean quarantine);

    BusinessEventDeliveryBacklog backlog(
            String consumerId, AuthorizedReadScope scope, long afterSequence);

    enum ClaimState {
        CLAIMED,
        DELIVERED,
        QUARANTINED,
        DEFERRED
    }

    record DeliveryClaim(
            String consumerId,
            String instanceId,
            UUID eventId,
            long eventSequence,
            int attemptNo,
            UUID leaseToken) {}

    record ClaimDecision(ClaimState state, DeliveryClaim claim) {
        public static ClaimDecision claimed(DeliveryClaim claim) {
            return new ClaimDecision(ClaimState.CLAIMED, claim);
        }

        public static ClaimDecision skipped(ClaimState state) {
            return new ClaimDecision(state, null);
        }
    }
}
