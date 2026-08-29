package com.cofco.qiqihar.graintrade.overview.application;

public record OverviewSamplePointAggregate(
        String regionCode,
        String regionName,
        String regionLevel,
        String scopeKind,
        String anchorRegionCode,
        long samplePointCount,
        long productionCount,
        long marketCount,
        long logisticsCount,
        long validCoordinateCount,
        long dataQualityIssueCount,
        long correctionSourceCount,
        long unresolvedSourceCount) {}
