package com.cofco.qiqihar.graintrade.overview.application;

import java.util.List;
import java.util.UUID;

public record OverviewSamplePointIcon(
        UUID samplePointId,
        String name,
        List<TypeRef> types,
        double longitude,
        double latitude) {
    public OverviewSamplePointIcon { types = List.copyOf(types); }
    public record TypeRef(String code, String name) {}
}
