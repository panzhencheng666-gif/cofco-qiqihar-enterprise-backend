package com.cofco.qiqihar.graintrade.overview.api;

import java.util.List;
import java.util.Set;

public interface CurrentOverviewSamplePointReader {
    List<CurrentOverviewSamplePoint> read(
            int year,
            String productCode,
            String regionCode,
            Set<String> authorizedRegionCodes);
}
