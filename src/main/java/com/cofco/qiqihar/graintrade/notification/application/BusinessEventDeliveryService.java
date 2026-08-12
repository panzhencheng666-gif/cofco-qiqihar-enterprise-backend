package com.cofco.qiqihar.graintrade.notification.application;

import com.cofco.qiqihar.graintrade.notification.application.BusinessEventDeliveryRepository.ClaimDecision;
import com.cofco.qiqihar.graintrade.notification.application.BusinessEventDeliveryRepository.ClaimState;
import com.cofco.qiqihar.graintrade.notification.application.BusinessEventDeliveryRepository.DeliveryClaim;
import com.cofco.qiqihar.graintrade.shared.security.application.AuthorizedReadScope;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class BusinessEventDeliveryService {
    private static final Duration DEFAULT_LEASE = Duration.ofSeconds(10);
    private static final Duration DEFAULT_BASE_RETRY = Duration.ofSeconds(1);
    private static final Duration DEFAULT_MAXIMUM_RETRY = Duration.ofSeconds(30);
    private static final int DEFAULT_MAX_ATTEMPTS = 5;

    private final BusinessNotificationRepository notifications;
    private final BusinessEventDeliveryRepository deliveries;
    private final Clock clock;
    private final Duration leaseDuration;
    private final Duration baseRetry;
    private final Duration maximumRetry;
    private final int maximumAttempts;

    @Autowired
    public BusinessEventDeliveryService(
            BusinessNotificationRepository notifications,
            BusinessEventDeliveryRepository deliveries,
            Clock clock) {
        this(notifications, deliveries, clock, DEFAULT_LEASE, DEFAULT_BASE_RETRY,
                DEFAULT_MAXIMUM_RETRY, DEFAULT_MAX_ATTEMPTS);
    }

    public BusinessEventDeliveryService(
            BusinessNotificationRepository notifications,
            BusinessEventDeliveryRepository deliveries,
            Clock clock,
            Duration leaseDuration,
            Duration baseRetry,
            Duration maximumRetry,
            int maximumAttempts) {
        this.notifications = notifications;
        this.deliveries = deliveries;
        this.clock = clock;
        this.leaseDuration = positive(leaseDuration, "leaseDuration");
        this.baseRetry = positive(baseRetry, "baseRetry");
        this.maximumRetry = positive(maximumRetry, "maximumRetry");
        if (maximumRetry.compareTo(baseRetry) < 0) {
            throw new IllegalArgumentException("maximumRetry must not be shorter than baseRetry");
        }
        if (maximumAttempts < 1) {
            throw new IllegalArgumentException("maximumAttempts must be positive");
        }
        this.maximumAttempts = maximumAttempts;
    }

    public DrainResult drain(
            String consumerId,
            String instanceId,
            AuthorizedReadScope scope,
            String subjectId,
            long afterSequence,
            int limit,
            DeliverySink sink) {
        Instant now = clock.instant();
        deliveries.ensureCheckpoint(consumerId, instanceId, afterSequence, now);
        OptionalRetry pollRetry = pollRetry(consumerId, now);
        if (pollRetry.deferred()) {
            return new DrainResult(afterSequence, 0, 0, pollRetry.delay(), false);
        }

        List<BusinessNotification> events;
        try {
            events = notifications.findVisibleAfter(scope, subjectId, afterSequence, limit);
            deliveries.recordPollSucceeded(consumerId, instanceId, afterSequence, now);
        } catch (RuntimeException queryFailure) {
            Duration retry = deliveries.recordPollFailed(consumerId, instanceId, afterSequence,
                    "EVENT_QUERY_FAILED", safeMessage(queryFailure), now, baseRetry, maximumRetry);
            return new DrainResult(afterSequence, 0, 0, retry, true);
        }

        long resumeSequence = afterSequence;
        boolean contiguous = true;
        int deliveredCount = 0;
        int failedCount = 0;
        Duration retryAfter = Duration.ZERO;
        for (BusinessNotification event : events) {
            Instant attemptTime = clock.instant();
            ClaimDecision decision = deliveries.claim(consumerId, instanceId, event, attemptTime,
                    attemptTime.plus(leaseDuration));
            boolean terminal = decision.state() == ClaimState.DELIVERED
                    || decision.state() == ClaimState.QUARANTINED;
            if (decision.state() == ClaimState.CLAIMED) {
                DeliveryClaim claim = decision.claim();
                try {
                    sink.send(event);
                    if (!deliveries.markDelivered(claim, clock.instant())) {
                        throw new IllegalStateException("Delivery lease was lost before completion");
                    }
                    deliveredCount++;
                    terminal = true;
                } catch (Exception deliveryFailure) {
                    failedCount++;
                    boolean quarantine = claim.attemptNo() >= maximumAttempts;
                    Duration delay = quarantine ? Duration.ZERO : retryDelay(claim.attemptNo());
                    Instant failedAt = clock.instant();
                    deliveries.markFailed(claim, "EVENT_DELIVERY_FAILED", safeMessage(deliveryFailure),
                            failedAt, quarantine ? null : failedAt.plus(delay), quarantine);
                    terminal = quarantine;
                    if (!quarantine && (retryAfter.isZero() || delay.compareTo(retryAfter) < 0)) {
                        retryAfter = delay;
                    }
                }
            }
            if (contiguous && terminal) {
                resumeSequence = event.sequence();
            } else if (!terminal) {
                contiguous = false;
            }
        }
        return new DrainResult(resumeSequence, deliveredCount, failedCount, retryAfter, false);
    }

    public BusinessEventDeliveryBacklog backlog(
            String consumerId, AuthorizedReadScope scope, long afterSequence) {
        return deliveries.backlog(consumerId, scope, afterSequence);
    }

    private OptionalRetry pollRetry(String consumerId, Instant now) {
        return deliveries.pollRetryAt(consumerId)
                .filter(retryAt -> retryAt.isAfter(now))
                .map(retryAt -> new OptionalRetry(true, Duration.between(now, retryAt)))
                .orElseGet(() -> new OptionalRetry(false, Duration.ZERO));
    }

    private Duration retryDelay(int attemptNo) {
        long multiplier = 1L << Math.min(30, Math.max(0, attemptNo - 1));
        try {
            Duration calculated = baseRetry.multipliedBy(multiplier);
            return calculated.compareTo(maximumRetry) > 0 ? maximumRetry : calculated;
        } catch (ArithmeticException overflow) {
            return maximumRetry;
        }
    }

    private static String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            message = failure.getClass().getSimpleName();
        }
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }

    private static Duration positive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    @FunctionalInterface
    public interface DeliverySink {
        void send(BusinessNotification event) throws Exception;
    }

    public record DrainResult(
            long resumeSequence,
            int deliveredCount,
            int failedCount,
            Duration retryAfter,
            boolean queryFailed) {}

    private record OptionalRetry(boolean deferred, Duration delay) {}
}
