package com.cofco.qiqihar.graintrade.reporting.application;

import java.time.Instant;
import java.util.List;

public record ActivityReport(
        String kind,
        int periodDays,
        Instant periodStart,
        Instant periodEnd,
        Instant eventCutoff,
        Subject subject,
        long effectiveUserCount,
        long totalEvents,
        long samplePointsCreated,
        long samplePointsDeleted,
        List<Count> actions,
        List<Count> domains,
        List<Count> workUnits,
        String scopeNotice) {

    public ActivityReport {
        actions = List.copyOf(actions);
        domains = List.copyOf(domains);
        workUnits = List.copyOf(workUnits);
    }

    public record Subject(String subjectId, String displayName, String workUnitName) {}
    public record Count(String code, String label, long count) {}
}
