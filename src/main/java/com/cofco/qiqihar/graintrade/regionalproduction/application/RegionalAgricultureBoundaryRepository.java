package com.cofco.qiqihar.graintrade.regionalproduction.application;

import java.math.BigDecimal;
import java.util.Optional;

public interface RegionalAgricultureBoundaryRepository {
    Optional<BigDecimal> areaSquareMetres(String regionCode);

    RegionFacts facts(String regionCode);

    default Optional<Allocation> allocation(String regionCode, String parentCode) {
        return areaSquareMetres(regionCode).flatMap(area -> areaSquareMetres(parentCode)
                .filter(parent -> parent.signum() > 0)
                .map(parent -> new Allocation(area.divide(parent, 12, java.math.RoundingMode.HALF_UP),
                        "本地区参考面积" + area + "平方米÷上级参考面积" + parent + "平方米")));
    }

    record Allocation(BigDecimal share, String basis) {}

    record RegionFacts(
            BigDecimal areaSquareMetres,
            int directChildCount,
            int countyCount,
            int townshipCount,
            int villageCount) {}
}
