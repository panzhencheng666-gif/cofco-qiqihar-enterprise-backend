package com.cofco.qiqihar.graintrade.notification.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record BusinessNotification(
        UUID id,
        long sequence,
        String aggregateType,
        String aggregateId,
        String actionCode,
        String productCode,
        Integer surveyYear,
        List<String> regionCodes,
        Instant occurredAt,
        boolean read) {
    public BusinessNotification {
        regionCodes = List.copyOf(regionCodes);
    }
}
