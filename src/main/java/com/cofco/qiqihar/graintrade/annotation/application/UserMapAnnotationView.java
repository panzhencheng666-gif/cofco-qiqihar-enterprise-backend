package com.cofco.qiqihar.graintrade.annotation.application;

import java.math.BigDecimal;
import java.time.Instant;

public record UserMapAnnotationView(
        String type,
        BigDecimal minLongitude,
        BigDecimal minLatitude,
        BigDecimal maxLongitude,
        BigDecimal maxLatitude,
        String regionCode,
        String administrativeLevel,
        long version,
        Instant updatedAt) {}
