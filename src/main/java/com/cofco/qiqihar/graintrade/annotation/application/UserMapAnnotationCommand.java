package com.cofco.qiqihar.graintrade.annotation.application;

import java.math.BigDecimal;

public record UserMapAnnotationCommand(
        String type,
        BigDecimal minLongitude,
        BigDecimal minLatitude,
        BigDecimal maxLongitude,
        BigDecimal maxLatitude,
        String regionCode,
        String administrativeLevel) {}
