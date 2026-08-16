package com.cofco.qiqihar.graintrade.analysis.application;

import java.util.Set;

public interface ObservableAnalysisRepository {
    boolean knownProduct(String productCode);
    boolean knownRegion(String regionCode);
    boolean knownCultivar(String productCode, String cultivarCode);
    boolean knownSubjectType(String domain, String subjectTypeCode);
    boolean canNavigateRegion(String regionCode, Set<String> authorizedRegionCodes);
    ObservableAnalysisSnapshot load(
            ObservableAnalysisScope scope, Set<String> authorizedRegionCodes);
}
