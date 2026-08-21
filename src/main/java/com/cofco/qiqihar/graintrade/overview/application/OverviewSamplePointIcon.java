package com.cofco.qiqihar.graintrade.overview.application;

import java.util.List;
import java.util.UUID;

public record OverviewSamplePointIcon(
        UUID samplePointId,
        String name,
        String iconKey,
        List<TypeRef> types,
        double longitude,
        double latitude,
        String dataQualityReason) {
    public OverviewSamplePointIcon { types = List.copyOf(types); }
    public record TypeRef(String code, String name, String iconKey) {}
}
