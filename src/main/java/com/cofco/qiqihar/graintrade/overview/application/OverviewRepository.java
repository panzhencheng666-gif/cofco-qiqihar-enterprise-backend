package com.cofco.qiqihar.graintrade.overview.application;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface OverviewRepository {
    OverviewOptions options();
    OverviewMapScope mapScope();
    boolean knownProduct(String productCode);
    boolean knownRegion(String regionCode);
    boolean knownPeriod(String periodCode);
    Optional<Integer> surveyYearForPeriod(String periodCode);
    boolean knownCultivar(String productCode, String cultivarCode);
    List<AnnualComparisonDefinition> annualComparisonDefinitions(String sourceDomain, String productCode);
    Optional<AnnualComparisonDefinition> annualComparisonDefinition(String indicatorCode);
    boolean canNavigateRegion(String regionCode, Set<String> authorizedRegionCodes);
    List<OverviewRegion> regions(String parentCode, String productCode, int year, Set<String> authorizedRegionCodes);
    List<OverviewRegion> locations(String ancestorCode, String level, String productCode, int year, Set<String> authorizedRegionCodes);
    List<OverviewIndicator> indicators(String productCode, String regionCode, int year,
            Set<String> authorizedRegionCodes);
    OverviewDashboard dashboard(String productCode, int year, String regionCode,
            Set<String> authorizedRegionCodes);
    List<AnnualComparisonPoint> annualComparison(String productCode, String cultivarCode, String regionCode,
            int surveyYear, AnnualComparisonDefinition definition, Set<String> authorizedRegionCodes);
}
