package com.cofco.qiqihar.graintrade.notification.application;

import java.time.Instant;

public record BusinessEventDeliveryBacklog(
        long pendingCount,
        long retryScheduledCount,
        long inProgressCount,
        long quarantinedCount,
        Instant oldestPendingAt) {}
