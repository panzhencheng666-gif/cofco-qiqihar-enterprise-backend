package com.cofco.qiqihar.graintrade.samplepoint.coordinate.application;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

public interface SamplePointCoordinateGuard {
    void lockAndRequireAvailable(
            UUID allowedSamplePointId, BigDecimal longitude, BigDecimal latitude);

    void lockAndRequireReviewedSharing(
            UUID allowedSamplePointId, BigDecimal longitude, BigDecimal latitude,
            Set<UUID> reviewedOccupantIds);
}
