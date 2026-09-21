package com.cofco.qiqihar.graintrade.risk.application;

import java.util.UUID;

public record RiskScoringTask(
        UUID modelId,int modelVersion,String artifactReference,String artifactSha256,
        UUID assessmentId,String canonicalEvidence,String lifecyclePhase) { }
