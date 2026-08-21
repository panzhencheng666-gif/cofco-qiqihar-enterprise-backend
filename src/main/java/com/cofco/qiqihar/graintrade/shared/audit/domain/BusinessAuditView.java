package com.cofco.qiqihar.graintrade.shared.audit.domain;

import java.time.Instant;
import java.util.UUID;

public record BusinessAuditView(
        UUID eventId,
        String aggregateType,
        String aggregateId,
        String actionCode,
        String actorSubjectId,
        String actorDisplayName,
        String workUnitCode,
        String workUnitName,
        Instant occurredAt,
        String detailJson) {
}
