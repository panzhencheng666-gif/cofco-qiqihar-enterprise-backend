package com.cofco.qiqihar.graintrade.workflow.application;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public record WorkObligationWeeklyReport(
        LocalDate weekStart,
        LocalDate weekEnd,
        String scopeLabel,
        Summary summary,
        List<Row> rows) {

    public WorkObligationWeeklyReport {
        rows = List.copyOf(rows);
    }

    public record Summary(
            long total,
            long onTime,
            long lateCompleted,
            long overdueOutstanding,
            long pending,
            long returned) {}

    public record Row(
            String workItemId,
            String employeeSubjectId,
            String employeeName,
            String workUnitCode,
            String workUnitName,
            String businessDomain,
            String businessDomainLabel,
            String regionCode,
            String regionName,
            String productName,
            String businessPeriod,
            OffsetDateTime dueAt,
            OffsetDateTime completedAt,
            String statusCode,
            String statusLabel,
            String complianceCode,
            String complianceLabel,
            String sourceType,
            String sourceId) {}
}
