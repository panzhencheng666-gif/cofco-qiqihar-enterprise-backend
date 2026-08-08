package com.cofco.qiqihar.graintrade.overview.application;

import java.math.BigDecimal;

public record AnnualComparisonPoint(
        String businessYear,
        BigDecimal value,
        String sourcePublicationVersion,
        String dataCutoff,
        String missingReason) {}
