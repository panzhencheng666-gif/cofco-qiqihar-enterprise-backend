package com.cofco.qiqihar.graintrade.overview.application;

import java.util.List;

public interface OverviewRepository {
    OverviewOptions options();
    boolean knownProduct(String productCode);
    boolean knownRegion(String regionCode);
    boolean knownPeriod(String periodCode);
    List<OverviewRegion> regions(String parentCode, String productCode, String periodCode);
    List<OverviewIndicator> indicators(String productCode, String regionCode, String periodCode, String marketingYear);
}
