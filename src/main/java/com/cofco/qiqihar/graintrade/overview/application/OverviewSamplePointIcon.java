package com.cofco.qiqihar.graintrade.overview.application;

import java.util.List;
import java.util.UUID;

public record OverviewSamplePointIcon(
        UUID samplePointId,
        String name,
        String regionCode,
        String iconKey,
        List<RoleRef> roles,
        List<TypeRef> types,
        double longitude,
        double latitude,
        String dataQualityReason, String locationMode) {
    public OverviewSamplePointIcon(UUID samplePointId, String name, String regionCode, String iconKey,
            List<RoleRef> roles, List<TypeRef> types, double longitude, double latitude, String dataQualityReason) {
        this(samplePointId,name,regionCode,iconKey,roles,types,longitude,latitude,dataQualityReason,null);
    }
    public OverviewSamplePointIcon {
        roles = List.copyOf(roles);
        types = List.copyOf(types);
    }
    public record RoleRef(String code, String name, String iconKey) {}
    public record TypeRef(String code, String name, String iconKey) {}
}
