package com.cofco.qiqihar.graintrade.risk.application;

public record RiskPredictionOutcome(
        boolean actualPositive,boolean candidatePredictedPositive,
        Boolean incumbentPredictedPositive) { }
