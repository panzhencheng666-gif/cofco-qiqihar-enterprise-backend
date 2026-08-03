package com.cofco.qiqihar.graintrade.supply.application;

public record UpstreamSourceReleaseCommand(
        String sourceDomain,
        String sourceRecordId,
        long sourceVersion,
        String productCode,
        String regionCode,
        String marketingYear,
        String roleCode,
        String sourceFieldCode,
        String unitCode,
        String qualityState) {}
