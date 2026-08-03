package com.cofco.qiqihar.graintrade.supply.application;
public record SupplySourceView(
        String roleCode,
        String roleLabel,
        String groupCode,
        String sourceDomain,
        String sourceRecordId,
        long sourceVersion,
        String sourceFieldCode,
        String unitCode,
        String approvalState,
        String approvedAt,
        String qualityState,
        String sourceValue,
        String adoptedValue,
        String reason,
        String drillDownRoute) {}
