package com.cofco.qiqihar.graintrade.production.application;

public record ProductionFactDefinition(
        String code, String category, String label, String valueType, String unit,
        String description, int precision, int scale) { }
