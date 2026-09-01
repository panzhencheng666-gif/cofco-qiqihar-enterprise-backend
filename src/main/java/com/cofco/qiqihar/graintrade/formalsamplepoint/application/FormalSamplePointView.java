package com.cofco.qiqihar.graintrade.formalsamplepoint.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record FormalSamplePointView(
        UUID id,
        String kindCode,
        String canonicalName,
        String regionCode,
        String objectTypeCode,
        String objectTypeName,
        String businessDomain,
        String address,
        String approvalState,
        String locationState,
        BigDecimal longitude,
        BigDecimal latitude,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        long version,
        long annualObservationCount,
        long networkMembershipCount) {}
