package com.cofco.qiqihar.graintrade.overview.api;

import java.util.List;
import java.util.Set;

public interface CurrentOverviewSamplePointReader {
    default List<CurrentOverviewSamplePoint> read(
            int year,
            String productCode,
            String regionCode,
            Set<String> authorizedRegionCodes) {
        return read(year, productCode, regionCode, null, authorizedRegionCodes);
    }

    List<CurrentOverviewSamplePoint> read(
            int year,
            String productCode,
            String regionCode,
            String categoryCode,
            Set<String> authorizedRegionCodes);
}
