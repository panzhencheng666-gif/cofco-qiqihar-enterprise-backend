package com.cofco.qiqihar.graintrade.overview.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OverviewHistoricalSamplePointDetail(
        UUID samplePointId,
        String name,
        String regionCode,
        Instant retiredAt,
        int retirementYear,
        String retirementReason,
        String retiredBy,
        List<OverviewSamplePointIcon.RoleRef> roles,
        List<OverviewSamplePointDetail.Association> lastBusinessData) {
    public OverviewHistoricalSamplePointDetail {
        roles = List.copyOf(roles);
        lastBusinessData = List.copyOf(lastBusinessData);
    }
}
