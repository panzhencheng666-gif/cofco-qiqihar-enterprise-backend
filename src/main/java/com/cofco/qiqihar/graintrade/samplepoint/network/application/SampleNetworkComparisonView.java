package com.cofco.qiqihar.graintrade.samplepoint.network.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SampleNetworkComparisonView(
        int networkYear,
        String networkStatus,
        int designPointCount,
        int designCoordinateCount,
        int activeSamplePointCount,
        int approvedSubmissionSamplePointCount,
        int pendingVerificationDesignPointCount,
        int multipleActualPerDesignPointCount,
        int anomalyCount,
        int exactCoveredDesignPointCount,
        int representedDesignPointCount,
        int regionalAssociationDesignPointCount,
        int unrelatedDesignPointCount,
        LevelCounts actualLevelCounts,
        List<DesignPoint> designPoints,
        List<ActualPoint> actualPoints,
        List<Relation> relations) {

    public SampleNetworkComparisonView {
        designPoints = List.copyOf(designPoints);
        actualPoints = List.copyOf(actualPoints);
        relations = List.copyOf(relations);
    }

    public record LevelCounts(int prefecture, int county, int township, int village) {}

    public record DesignPoint(
            String villageRegionCode,
            String villageName,
            String townshipRegionCode,
            String townshipName,
            String countyRegionCode,
            String countyName,
            BigDecimal designLongitude,
            BigDecimal designLatitude,
            String coordinateSourceName,
            String coordinateSourceRevision,
            String coordinateMatchConfidence,
            String coordinateReviewStatus) {}

    public record ActualPoint(
            UUID samplePointId,
            String samplePointName,
            String samplePointKindCode,
            String membershipStatusCode,
            String locatedRegionCode,
            String locatedRegionName,
            String locatedRegionLevel,
            BigDecimal actualLongitude,
            BigDecimal actualLatitude,
            String locationState) {}

    public record Relation(
            UUID samplePointId,
            String designVillageRegionCode,
            String relationType,
            String evidenceReference,
            String reviewStatus,
            String createdBy,
            Instant createdAt,
            String reviewedBy,
            Instant reviewedAt) {}
}
