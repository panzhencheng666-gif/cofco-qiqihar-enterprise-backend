package com.cofco.qiqihar.graintrade.overview.application;

import java.util.List;

/** Read-only four-year comparison over approved overview source facts. */
public record AnnualComparisonView(
        String indicatorCode,
        String indicatorName,
        String sourceDomain,
        String productCode,
        String cultivarCode,
        String regionCode,
        String cutoffPeriodCode,
        String unitCode,
        String methodologyVersion,
        List<AnnualComparisonPoint> points) {}
