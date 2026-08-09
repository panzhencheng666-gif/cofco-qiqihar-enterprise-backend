package com.cofco.qiqihar.graintrade.identity.application;

public record AccessReviewDecision(
        String subjectId,
        String grantType,
        String grantKey,
        String decisionCode,
        String reason) {}
