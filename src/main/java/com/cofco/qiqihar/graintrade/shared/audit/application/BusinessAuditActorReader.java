package com.cofco.qiqihar.graintrade.shared.audit.application;

import java.util.Optional;

public interface BusinessAuditActorReader {
    Optional<String> latestActor(String aggregateType, String aggregateId, String actionCode);
}
