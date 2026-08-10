package com.cofco.qiqihar.graintrade.supply.application;

import java.math.BigDecimal;
import java.time.Instant;

public record SupplySourceReleasePersistence(
        UpstreamSourceReleaseCommand command,
        SupplyTemporalContext temporalContext,
        SupplySourceReleaseMaterial material,
        BigDecimal accountValue,
        String immutableDigest,
        String actor,
        Instant occurredAt) {}
