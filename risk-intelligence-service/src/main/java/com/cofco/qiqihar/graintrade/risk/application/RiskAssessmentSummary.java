package com.cofco.qiqihar.graintrade.risk.application;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record RiskAssessmentSummary(
        UUID assessmentId,
        String domainCode,
        String subjectType,
        String subjectId,
        String evaluationMode,
        String riskLevel,
        List<String> reasonCodes,
        BigDecimal score,
        OffsetDateTime evaluatedAt,
        int evaluationDurationMs,
        String modelName,
        Integer modelVersion,
        boolean reviewed,
        String conclusionCode) { }
