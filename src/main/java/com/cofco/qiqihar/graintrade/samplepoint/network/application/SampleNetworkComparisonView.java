package com.cofco.qiqihar.graintrade.samplepoint.network.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record SampleNetworkComparisonView(
        int networkYear,
        String networkStatus,
        int designPointCount,
        int activeSamplePointCount,
        int coveredDesignPointCount,
        int uncoveredDesignPointCount,
        List<Point> points) {

    public SampleNetworkComparisonView {
        points = List.copyOf(points);
    }

    public record Point(
            String villageRegionCode,
            String villageName,
            String townshipRegionCode,
            String townshipName,
            String countyRegionCode,
            String countyName,
            BigDecimal designLongitude,
            BigDecimal designLatitude,
            UUID samplePointId,
            String samplePointName,
            String samplePointKindCode,
            String membershipStatusCode,
            BigDecimal actualLongitude,
            BigDecimal actualLatitude,
            String comparisonState) {}
}
