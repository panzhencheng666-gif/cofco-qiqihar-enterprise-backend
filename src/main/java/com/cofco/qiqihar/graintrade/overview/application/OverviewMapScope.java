package com.cofco.qiqihar.graintrade.overview.application;

public record OverviewMapScope(
        String scopeCode,
        String name,
        String boundaryGeoJson,
        String sourceName,
        String sourceRevision,
        String sourceLicense,
        String componentGeometryFingerprint,
        String refreshedAt) {}
