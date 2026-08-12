package com.cofco.qiqihar.graintrade.overview.application;

public record OverviewIndicator(String code, String name, String unitCode, String value,
        String sourceDomain, long sourceCount, String sourcePath, String formula,
        String sourceRelation, String dataCutoff, String coverageScope, String coverageStatus,
        String calculationVersion) {}
