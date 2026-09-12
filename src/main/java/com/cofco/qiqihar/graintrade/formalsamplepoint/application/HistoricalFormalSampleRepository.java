package com.cofco.qiqihar.graintrade.formalsamplepoint.application;

import com.cofco.qiqihar.graintrade.shared.application.PagedResult;
import java.util.Set;

public interface HistoricalFormalSampleRepository {
    PagedResult<HistoricalFormalSample> findPage(String domain, String product, Integer year,
            String region, String keyword, int page, int size, Set<String> authorizedRegions);
}
