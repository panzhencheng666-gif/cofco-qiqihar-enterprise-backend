package com.cofco.qiqihar.graintrade.overview.application;

import java.util.List;
import java.util.Set;

public interface OverviewRepository {
    OverviewOptions options();
    OverviewMapScope mapScope();
    boolean knownProduct(String productCode);
    boolean knownRegion(String regionCode);
    boolean knownPeriod(String periodCode);
    boolean canNavigateRegion(String regionCode, Set<String> authorizedRegionCodes);
    List<OverviewRegion> regions(String parentCode, String productCode, String periodCode, Set<String> authorizedRegionCodes);
    List<OverviewRegion> locations(String ancestorCode, String level, String productCode, String periodCode, Set<String> authorizedRegionCodes);
    List<OverviewIndicator> indicators(String productCode, String regionCode, String periodCode, String marketingYear,
            Set<String> authorizedRegionCodes);
    OverviewDashboard dashboard(String productCode, String periodCode, String regionCode, String marketingYear,
            Set<String> authorizedRegionCodes);
}
