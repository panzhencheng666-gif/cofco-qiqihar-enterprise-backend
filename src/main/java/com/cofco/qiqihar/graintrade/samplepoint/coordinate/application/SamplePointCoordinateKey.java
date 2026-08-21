package com.cofco.qiqihar.graintrade.samplepoint.coordinate.application;

import java.math.BigDecimal;
import java.util.Objects;

public record SamplePointCoordinateKey(BigDecimal longitude, BigDecimal latitude) {
    public SamplePointCoordinateKey {
        Objects.requireNonNull(longitude, "longitude");
        Objects.requireNonNull(latitude, "latitude");
    }

    public static SamplePointCoordinateKey of(BigDecimal longitude, BigDecimal latitude) {
        return new SamplePointCoordinateKey(
                longitude.stripTrailingZeros(), latitude.stripTrailingZeros());
    }

    public String lockKey() {
        return longitude.toPlainString() + ":" + latitude.toPlainString();
    }
}
