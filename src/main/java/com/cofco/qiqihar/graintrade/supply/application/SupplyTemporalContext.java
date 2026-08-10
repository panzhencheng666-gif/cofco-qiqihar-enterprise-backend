package com.cofco.qiqihar.graintrade.supply.application;

public record SupplyTemporalContext(
        String periodCode,
        int surveyYear,
        String surveyQuarter,
        String periodPrecision,
        String marketingYear) {}
