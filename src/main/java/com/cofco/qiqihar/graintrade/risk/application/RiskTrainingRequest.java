package com.cofco.qiqihar.graintrade.risk.application;

import java.time.Instant;
import java.util.UUID;

public record RiskTrainingRequest(
        UUID executionId,UUID modelId,String statusCode,Instant createdAt) { }
