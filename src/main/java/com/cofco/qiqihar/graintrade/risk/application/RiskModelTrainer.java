package com.cofco.qiqihar.graintrade.risk.application;

public interface RiskModelTrainer {
    default boolean supports(String modelKind) { return true; }
    RiskTrainingArtifact train(RiskTrainingJob job) throws Exception;
}
