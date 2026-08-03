package com.cofco.qiqihar.graintrade.shared.audit.domain;

import java.time.Instant;
import java.util.UUID;

public record BusinessAuditEvent(
        UUID id,
        String aggregateType,
        String aggregateId,
        String actionCode,
        String actorSubjectId,
        String workUnitCode,
        Instant occurredAt,
        String detailJson) {
    public BusinessAuditEvent {
        if (aggregateType == null || aggregateType.isBlank() || aggregateId == null || aggregateId.isBlank()
                || actionCode == null || actionCode.isBlank() || actorSubjectId == null || actorSubjectId.isBlank()
                || workUnitCode == null || workUnitCode.isBlank() || detailJson == null || detailJson.isBlank()) {
            throw new IllegalArgumentException("Business audit event is incomplete");
        }
    }
}
