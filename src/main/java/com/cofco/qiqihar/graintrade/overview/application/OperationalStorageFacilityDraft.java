package com.cofco.qiqihar.graintrade.overview.application;

import java.math.BigDecimal;
import java.time.LocalDate;

public record OperationalStorageFacilityDraft(
        String name,
        String relationType,
        String regionCode,
        String address,
        BigDecimal longitude,
        BigDecimal latitude,
        String operationalStatus,
        BigDecimal capacityTonnes,
        LocalDate capacityAsOf,
        LocalDate validFrom,
        LocalDate validTo,
        long expectedVersion) {}
