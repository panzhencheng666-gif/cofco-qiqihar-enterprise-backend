package com.cofco.qiqihar.graintrade.supply.application;

import java.util.List;

public record SupplyInputSetCommand(
        String productCode,
        String regionCode,
        String marketingYear,
        String reason,
        long expectedVersion,
        List<Item> items) {
    public record Item(String roleCode, String sourceReleaseId) {}
}
