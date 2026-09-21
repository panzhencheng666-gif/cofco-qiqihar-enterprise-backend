package com.cofco.qiqihar.graintrade.regionalproduction.application;

import java.util.List;

public record RegionalEstimateBatch(String rootRegionCode, int year, String calculatedAt, String attemptedAt,
        String sourceCheckedAt, String calculationStatus, String sourceStatus, String modelVersion,
        List<RegionalCurrentEstimates.Comparison> comparisons) {}
