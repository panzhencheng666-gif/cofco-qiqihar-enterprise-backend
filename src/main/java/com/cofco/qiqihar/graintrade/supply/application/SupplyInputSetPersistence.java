package com.cofco.qiqihar.graintrade.supply.application;

import java.time.Instant;
import java.util.List;

public record SupplyInputSetPersistence(
        SupplyInputSetCommand command,
        long version,
        List<SupplyInputSetMaterial.Source> selectedSources,
        String actor,
        Instant occurredAt) {

    public SupplyInputSetPersistence {
        selectedSources = List.copyOf(selectedSources);
    }
}
