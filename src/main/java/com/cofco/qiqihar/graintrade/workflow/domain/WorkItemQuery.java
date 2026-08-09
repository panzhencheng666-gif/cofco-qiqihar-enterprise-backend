package com.cofco.qiqihar.graintrade.workflow.domain;

import java.util.Set;

public record WorkItemQuery(
        WorkItemScope scope,
        WorkItemStatus status,
        String domain,
        String regionId,
        String productCode,
        int pageNumber,
        int pageSize,
        Set<String> authorizedRegionCodes,
        String assignedSubjectId) {

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
        authorizedRegionCodes = Set.copyOf(authorizedRegionCodes);
        assignedSubjectId = assignedSubjectId == null ? "" : assignedSubjectId.trim();
    }

    public WorkItemQuery(WorkItemScope scope, WorkItemStatus status, String domain, String regionId,
            String productCode, int pageNumber, int pageSize) {
        this(scope, status, domain, regionId, productCode, pageNumber, pageSize, Set.of("*"));
    }

    public WorkItemQuery(WorkItemScope scope, WorkItemStatus status, String domain, String regionId,
            String productCode, int pageNumber, int pageSize, Set<String> authorizedRegionCodes) {
        this(scope, status, domain, regionId, productCode, pageNumber, pageSize,
                authorizedRegionCodes, "");
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

    public WorkItemQuery authorizedFor(Set<String> regionCodes) {
        return new WorkItemQuery(scope, status, domain, regionId, productCode, pageNumber, pageSize,
                regionCodes, assignedSubjectId);
    }

    public WorkItemQuery assignedTo(String subjectId) {
        return new WorkItemQuery(scope, status, domain, regionId, productCode, pageNumber, pageSize,
                authorizedRegionCodes, subjectId);
    }
}
