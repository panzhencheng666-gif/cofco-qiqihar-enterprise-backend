package com.cofco.qiqihar.graintrade.designsample.point.application;

import com.cofco.qiqihar.graintrade.designsample.metadata.domain.DesignSampleContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public record DesignSamplePointView(
        UUID id,
        String contractVersion,
        String contractDigest,
        DesignSampleContext context,
        JsonNode values,
        String name,
        String regionCode,
        String regionPath,
        BigDecimal longitude,
        BigDecimal latitude,
        long version,
        Instant updatedAt) {}
