package com.cofco.qiqihar.graintrade.formalsamplepoint.application;

import java.math.BigDecimal;

public record FormalSamplePointDraft(
        String canonicalName,
        String regionCode,
        String address,
        BigDecimal longitude,
        BigDecimal latitude,
        String objectTypeCode) {}
