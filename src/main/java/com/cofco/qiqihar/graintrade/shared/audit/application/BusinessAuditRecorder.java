package com.cofco.qiqihar.graintrade.shared.audit.application;

import com.cofco.qiqihar.graintrade.shared.audit.domain.BusinessAuditEvent;
import com.cofco.qiqihar.graintrade.shared.security.domain.SecurityPrincipal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class BusinessAuditRecorder {
    private final BusinessAuditWriter writer;

    public BusinessAuditRecorder(BusinessAuditWriter writer) {
        this.writer = writer;
    }

    public void record(SecurityPrincipal principal, String aggregateType, String aggregateId,
            String actionCode, Instant occurredAt, String detailJson) {
        writer.append(new BusinessAuditEvent(UUID.randomUUID(), aggregateType, aggregateId, actionCode,
                principal.subjectId(), principal.workUnitCode(), occurredAt, detailJson));
    }
}
