package com.cofco.qiqihar.graintrade.risk.application;

import java.time.Instant;
import java.util.UUID;

public record RiskFeedback(
        UUID feedbackId,
        UUID assessmentId,
        String conclusionCode,
        String reasonCode,
        String dispositionNote,
        String resolvedBySubject,
        Instant resolvedAt) { }
