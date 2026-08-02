package com.cofco.qiqihar.graintrade.workflow.domain;

public enum WorkItemScope {
    PENDING,
    COMPLETED;

    public boolean accepts(WorkItemStatus status) {
        return this == PENDING && status != null;
    }
}
