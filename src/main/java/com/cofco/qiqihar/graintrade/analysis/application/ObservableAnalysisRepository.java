package com.cofco.qiqihar.graintrade.analysis.application;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

public interface ObservableAnalysisRepository {
    boolean knownProduct(String productCode);
    boolean knownRegion(String regionCode);
    boolean knownCultivar(String productCode, String cultivarCode);
    boolean knownSubjectType(String domain, String subjectTypeCode);
    boolean canNavigateRegion(String regionCode, Set<String> authorizedRegionCodes);
    ObservableAnalysisSnapshot load(
            ObservableAnalysisScope scope, Set<String> authorizedRegionCodes);
    ObservableSupplySummary loadSupplySummary(
            ObservableAnalysisScope scope, Set<String> authorizedRegionCodes);
    default ObservableSupplySummary loadSupplySummary(
            ObservableAnalysisScope scope, Set<String> authorizedRegionCodes,
            Set<UUID> currentSamplePointIds) {
        return loadSupplySummary(scope, authorizedRegionCodes);
    }
    default ObservableSupplySummary loadSupplySummary(
            ObservableAnalysisScope scope, Set<String> authorizedRegionCodes,
            Supplier<Set<UUID>> currentSamplePointIds) {
        return loadSupplySummary(scope, authorizedRegionCodes, currentSamplePointIds.get());
    }
    default List<ObservableHeadlineMetric> loadHeadlineMetrics(
            ObservableAnalysisScope scope, Set<String> authorizedRegionCodes,
            Supplier<Set<UUID>> currentSamplePointIds) {
        return loadSupplySummary(scope, authorizedRegionCodes, currentSamplePointIds)
                .headlineMetrics();
    }
}
