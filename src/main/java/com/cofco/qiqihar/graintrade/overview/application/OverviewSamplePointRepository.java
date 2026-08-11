package com.cofco.qiqihar.graintrade.overview.application;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface OverviewSamplePointRepository {
    String regionLevel(String regionCode);
    boolean knownCategory(String categoryCode);
    boolean knownType(String categoryCode, String typeCode);
    List<OverviewSamplePointAggregate> aggregates(String parentCode, Set<String> authorizedRegionCodes);
    OverviewSamplePointList list(String regionCode, String categoryCode, String typeCode, String query,
            Set<String> authorizedRegionCodes);
    List<OverviewSamplePointIcon> icons(String regionCode, String categoryCode, String typeCode,
            Set<String> authorizedRegionCodes);
    Optional<OverviewSamplePointDetail> detail(UUID samplePointId, String regionCode,
            Set<String> authorizedRegionCodes);
}
