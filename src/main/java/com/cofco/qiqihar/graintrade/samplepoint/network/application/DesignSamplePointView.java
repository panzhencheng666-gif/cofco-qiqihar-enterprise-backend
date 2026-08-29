package com.cofco.qiqihar.graintrade.samplepoint.network.application;

import java.math.BigDecimal;

public record DesignSamplePointView(
        String villageRegionCode,
        String villageName,
        String townshipRegionCode,
        String townshipName,
        String countyRegionCode,
        String countyName,
        BigDecimal longitude,
        BigDecimal latitude,
        String coordinateSourceName,
        String coordinateSourceRevision,
        String coordinateMatchConfidence,
        String coordinateReviewStatus) {}
