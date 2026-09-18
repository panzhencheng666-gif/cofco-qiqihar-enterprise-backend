package com.cofco.qiqihar.graintrade.regionalproduction.application;

import java.util.List;

public interface RegionalRailwayRepository {
    RegionalRailways find(String regionCode);

    default RegionalRailways findFacilities(String regionCode) {
        return find(regionCode);
    }

    List<RegionalRailwayRoute> findRoutes(String regionCode);
}
