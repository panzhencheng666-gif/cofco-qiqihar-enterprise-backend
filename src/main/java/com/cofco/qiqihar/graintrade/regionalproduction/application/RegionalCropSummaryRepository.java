package com.cofco.qiqihar.graintrade.regionalproduction.application;

import java.util.Optional;
import java.util.Set;

public interface RegionalCropSummaryRepository {
    Optional<RegionalCropSummary> summarize(
            int year, String productCode, String regionCode, Set<String> authorizedRegions);
}
