package com.cofco.qiqihar.graintrade.supply.application;

import java.util.List;

/** Database-owned input governance contract for one supply account context. */
public record SupplyInputWorkspaceView(
        String productCode,
        String regionCode,
        String marketingYear,
        long inputSetVersion,
        String latestInputSetId,
        long decisionVersion,
        List<Role> roles) {

    public record Role(
            String code,
            String label,
            String groupCode,
            boolean required,
            int sortOrder,
            boolean manualAllowed,
            long manualDecisionVersion,
            String selectedReleaseId,
            List<Release> releases) {}

    public record Release(
            String id,
            String sourceDomain,
            String sourceRecordId,
            long sourceVersion,
            String sourceFieldCode,
            String value,
            String unitCode,
            String qualityState,
            String approvedAt) {}
}
