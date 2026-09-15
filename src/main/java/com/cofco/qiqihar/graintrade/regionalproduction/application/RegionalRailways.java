package com.cofco.qiqihar.graintrade.regionalproduction.application;

import java.math.BigDecimal;
import java.util.List;

public record RegionalRailways(String regionCode, boolean boundaryAvailable, String sourceAsOf,
        List<Facility> facilities, List<Line> lines) {
    public record Facility(String sourceId, String name, String kind, BigDecimal longitude, BigDecimal latitude,
            String operator, String reference, String status, String service, String locationRelation,
            BigDecimal distanceKm, String nearbyLines, String sourceUrl) {}
    public record Line(String name, BigDecimal mappedTrackKm, String usage, String electrification,
            String gauge, String operator, String sourceUrl) {}
}
