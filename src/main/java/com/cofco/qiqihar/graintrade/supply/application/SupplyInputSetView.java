package com.cofco.qiqihar.graintrade.supply.application;

public record SupplyInputSetView(
        String id,
        long version,
        String productCode,
        String regionCode,
        String marketingYear) {}
