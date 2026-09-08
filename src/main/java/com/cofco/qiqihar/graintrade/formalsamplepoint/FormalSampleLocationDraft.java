package com.cofco.qiqihar.graintrade.formalsamplepoint;

import java.math.BigDecimal;

/** Location changes are versioned independently of the observation payload. */
public record FormalSampleLocationDraft(
        Long expectedVersion, String regionCode, BigDecimal longitude, BigDecimal latitude, String address) {
    public FormalSampleLocationDraft(Long expectedVersion, String regionCode, BigDecimal longitude, BigDecimal latitude) {
        this(expectedVersion, regionCode, longitude, latitude, null);
    }
}
