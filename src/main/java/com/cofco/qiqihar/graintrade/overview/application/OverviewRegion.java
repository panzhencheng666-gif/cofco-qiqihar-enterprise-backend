package com.cofco.qiqihar.graintrade.overview.application;

public record OverviewRegion(
        String code,
        String name,
        String parentCode,
        String level,
        long approvedRecordCount,
        String boundaryGeoJson,
        String locationGeoJson,
        String locationReviewStatus,
        boolean mapContextOnly) {}
