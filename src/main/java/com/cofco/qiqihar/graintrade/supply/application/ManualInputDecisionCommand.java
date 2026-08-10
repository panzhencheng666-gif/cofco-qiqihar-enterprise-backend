package com.cofco.qiqihar.graintrade.supply.application;

import java.math.BigDecimal;

public record ManualInputDecisionCommand(
        String productCode,
        String regionCode,
        String periodCode,
        String roleCode,
        BigDecimal value,
        String reason,
        long expectedVersion) {}
