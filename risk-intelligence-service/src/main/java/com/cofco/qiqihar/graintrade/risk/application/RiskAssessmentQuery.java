package com.cofco.qiqihar.graintrade.risk.application;

public record RiskAssessmentQuery(
        String domainCode,
        String riskLevel,
        String reviewStatus,
        String search,
        int limit) { }
