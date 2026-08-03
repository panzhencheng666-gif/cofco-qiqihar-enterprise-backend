package com.cofco.qiqihar.graintrade.supply.domain;

import java.math.BigDecimal;

public record AdoptionDecision(BigDecimal value, String reason, String actorId, long version) {
    public static AdoptionDecision create(BigDecimal value, String reason, String actorId, long version) {
        if (value == null || reason == null || reason.isBlank() || actorId == null || actorId.isBlank()
                || version < 0) throw new IllegalArgumentException("Adoption reason and actor are required");
        return new AdoptionDecision(value, reason.trim(), actorId, version);
    }
}
