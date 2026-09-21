package com.cofco.qiqihar.graintrade.risk.application;

import java.time.Instant;
import java.util.UUID;

public record RiskModelActivationSummary(
        UUID eventId,UUID modelId,String modelName,Integer fromVersion,int toVersion,
        String eventCode,String reasonCode,Instant occurredAt) { }
