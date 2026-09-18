package com.cofco.qiqihar.graintrade.regionalproduction.application;

public interface RegionalRailwayRepository {
    RegionalRailways find(String regionCode);

    default RegionalRailways findFacilities(String regionCode) {
        return find(regionCode);
    }
}
