package com.cofco.qiqihar.graintrade.supply.application;

public record SupplyReleaseView(
        String id,
        String sourceDomain,
        String sourceRecordId,
        long sourceVersion,
        String roleCode,
        String sourceFieldCode,
        String value,
        String unitCode,
        String approvalState,
        String qualityState) {}
