package com.cofco.qiqihar.graintrade.supply.application;

import java.time.Instant;

public record SupplyManualDecisionPersistence(
        ManualInputDecisionCommand command,
        SupplyTemporalContext temporalContext,
        SupplySourceReleaseMaterial.SourceMapping mapping,
        long version,
        String immutableDigest,
        String actor,
        Instant occurredAt) {}
