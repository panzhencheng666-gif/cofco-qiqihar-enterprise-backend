package com.cofco.qiqihar.graintrade.designsample.point.application;

import java.util.Set;

public record DesignSamplePointQuery(
        String domainCode,
        String productCode,
        String objectTypeCode,
        String regionCode,
        String keyword,
        int pageNumber,
        int pageSize,
        Set<String> authorizedRegionCodes) {
    public DesignSamplePointQuery {
        authorizedRegionCodes = Set.copyOf(authorizedRegionCodes);
    }
}
