package com.cofco.qiqihar.graintrade.overview.application;

import java.util.List;
import java.util.Set;

public interface OverviewRepository {
    OverviewOptions options();
    boolean knownProduct(String productCode);
    boolean knownRegion(String regionCode);
    boolean knownPeriod(String periodCode);
    List<OverviewRegion> regions(String parentCode, String productCode, String periodCode,
            Set<String> authorizedRegionCodes);
    List<OverviewIndicator> indicators(String productCode, String regionCode, String periodCode, String marketingYear,
            Set<String> authorizedRegionCodes);
}
