package com.cofco.qiqihar.graintrade.masterdata.domain;

public record SupplySurveyPeriod(
        String code,
        String name,
        int surveyYear,
        String surveyQuarter,
        String precision,
        String marketingYearCode,
        String marketingYearName) {
}
