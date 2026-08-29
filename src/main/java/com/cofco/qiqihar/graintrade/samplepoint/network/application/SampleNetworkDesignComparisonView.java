package com.cofco.qiqihar.graintrade.samplepoint.network.application;

import java.util.List;

public record SampleNetworkDesignComparisonView(
        int networkYear,
        String networkStatus,
        int designPointCount,
        int designCoordinateCount,
        int pendingVerificationDesignPointCount,
        List<SampleNetworkComparisonView.DesignPoint> designPoints,
        List<SampleNetworkComparisonView.Relation> relations) {

    public SampleNetworkDesignComparisonView {
        designPoints = List.copyOf(designPoints);
        relations = List.copyOf(relations);
    }
}
