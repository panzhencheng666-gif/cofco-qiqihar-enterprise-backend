package com.cofco.qiqihar.graintrade.overview.application;

public record OverviewSamplePointAggregate(
        String regionCode,
        String regionName,
        String regionLevel,
        long samplePointCount,
        long productionCount,
        long marketCount,
        long validCoordinateCount,
        long dataQualityIssueCount,
        long correctionSourceCount,
        long unresolvedSourceCount) {}
