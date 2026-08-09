package com.cofco.qiqihar.graintrade.workflow.domain;

import java.time.OffsetDateTime;

public record WorkItem(
        String id,
        String task,
        String domain,
        String regionCode,
        String region,
        String product,
        String businessPeriodCode,
        String businessPeriod,
        OffsetDateTime dueAt,
        String workflowNode,
        String statusCode,
        String status,
        String responsiblePartyCode,
        String responsibleParty,
        String sourceType,
        String sourceId) {
}
