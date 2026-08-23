package com.cofco.qiqihar.graintrade.samplepoint.network.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AnnualSampleNetworkView(
        int networkYear,
        String statusCode,
        Integer carriedFromYear,
        long version,
        String createdBy,
        Instant createdAt,
        String submittedBy,
        Instant submittedAt,
        String reviewedBy,
        Instant reviewedAt,
        String reviewReason,
        List<Membership> memberships) {

    public AnnualSampleNetworkView {
        memberships = List.copyOf(memberships);
    }

    public record Membership(
            UUID samplePointId,
            String samplePointName,
            String samplePointKindCode,
            String villageRegionCode,
            String villageName,
            String statusCode,
            String sourceCode,
            String decisionReason,
            long version,
            BigDecimal longitude,
            BigDecimal latitude) {}
}
