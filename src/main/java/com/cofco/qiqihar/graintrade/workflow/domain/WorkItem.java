package com.cofco.qiqihar.graintrade.workflow.domain;

import java.time.OffsetDateTime;

public record WorkItem(
        String id,
        String task,
        String domain,
        String region,
        String product,
        String businessPeriod,
        OffsetDateTime dueAt,
        String workflowNode,
        String status,
        String responsibleParty) {
}
