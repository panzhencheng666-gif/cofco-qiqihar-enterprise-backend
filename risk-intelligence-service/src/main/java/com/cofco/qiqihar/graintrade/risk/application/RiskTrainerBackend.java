package com.cofco.qiqihar.graintrade.risk.application;

public interface RiskTrainerBackend {
    boolean supports(String modelKind);
    RiskTrainingArtifact train(RiskTrainingJob job) throws Exception;
}
