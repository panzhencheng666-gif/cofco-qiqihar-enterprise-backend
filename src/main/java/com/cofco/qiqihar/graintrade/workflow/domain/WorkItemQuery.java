package com.cofco.qiqihar.graintrade.workflow.domain;

public record WorkItemQuery(
        WorkItemScope scope,
        WorkItemStatus status,
        String domain,
        String regionId,
        String productCode,
        int pageNumber,
        int pageSize) {

    public WorkItemQuery {
        if (scope == null) {
            throw new IllegalArgumentException("scope must not be null");
        }
        if (scope == WorkItemScope.COMPLETED && status != null) {
            throw new IllegalArgumentException("completed scope cannot have a status filter");
        }
        if (pageNumber < 0 || pageSize < 1) {
            throw new IllegalArgumentException("invalid pagination");
        }
    }

    public static WorkItemQuery of(
            WorkItemScope scope,
            String status,
            String domain,
            String regionId,
            String productCode,
            int pageNumber,
            int pageSize) {
        return new WorkItemQuery(
                scope,
                status == null ? null : WorkItemStatus.valueOf(status),
                domain,
                regionId,
                productCode,
                pageNumber,
                pageSize);
    }
}
