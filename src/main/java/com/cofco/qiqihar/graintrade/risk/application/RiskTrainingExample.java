package com.cofco.qiqihar.graintrade.risk.application;

import java.time.Instant;
import java.util.UUID;

public record RiskTrainingExample(
        UUID assessmentId,
        Instant resolvedAt,
        String canonicalText,
        boolean positive) { }
