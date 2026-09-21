package com.cofco.qiqihar.graintrade.risk.application;

public record RiskPromotionDecision(
        Status status,int resolvedLabelCount,double candidateF1,Double incumbentF1,
        String reasonCode) {
    public enum Status { WAITING, PASSED, REJECTED }
}
