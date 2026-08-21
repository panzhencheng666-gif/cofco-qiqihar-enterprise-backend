package com.cofco.qiqihar.graintrade.overview.application;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface OverviewSamplePointRepository {
    String regionLevel(String regionCode);
    boolean knownCategory(String categoryCode);
    boolean knownType(String categoryCode, String typeCode);
    List<OverviewSamplePointAggregate> aggregates(int year, String productCode, String parentCode,
            Set<String> authorizedRegionCodes);
    OverviewSamplePointList list(int year, String productCode, String regionCode, String categoryCode,
            String typeCode, String query,
            Set<String> authorizedRegionCodes);
    List<OverviewSamplePointIcon> icons(int year, String productCode, String regionCode,
            String categoryCode, String typeCode, String query,
            Set<String> authorizedRegionCodes);
    Optional<OverviewSamplePointDetail> detail(int year, String productCode, UUID samplePointId,
            String regionCode, String categoryCode, String typeCode,
            Set<String> authorizedRegionCodes);
}
