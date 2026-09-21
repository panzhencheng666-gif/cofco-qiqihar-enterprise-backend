package com.cofco.qiqihar.graintrade.risk.application;

public interface RiskLiveScorer {
    boolean supports(String modelKind);
    RiskModelScore score(RiskScoringTask task) throws Exception;
}
