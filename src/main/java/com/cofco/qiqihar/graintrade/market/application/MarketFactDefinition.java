package com.cofco.qiqihar.graintrade.market.application;

public record MarketFactDefinition(String code, String category, String label, String valueType,
        String unit, String description, int precision, int scale, int sortOrder) { }
