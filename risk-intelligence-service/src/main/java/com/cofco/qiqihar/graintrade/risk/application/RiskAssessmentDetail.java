package com.cofco.qiqihar.graintrade.risk.application;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public record RiskAssessmentDetail(
        RiskAssessmentSummary assessment,
        JsonNode evidenceSnapshot,
        AiJudgement judgement,
        RiskFeedback feedback) {

    public record AiJudgement(
            UUID judgementId,
            String independentConclusion,
            JsonNode supportingEvidence,
            JsonNode contradictingEvidence,
            JsonNode uncertaintyDefinition,
            JsonNode recommendedActions,
            BigDecimal confidence,
            OffsetDateTime generatedAt) { }
}
