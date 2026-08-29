package com.cofco.qiqihar.graintrade.samplepoint.coordinate.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record FormalSampleCoordinateChangeCommand(
        UUID samplePointId,
        Long expectedVersion,
        BigDecimal originalLongitude,
        BigDecimal originalLatitude,
        BigDecimal correctedLongitude,
        BigDecimal correctedLatitude,
        String coordinateSource,
        Instant coordinateCollectedAt,
        String verifiedAddress,
        String changeReason,
        String evidenceReference) {}
