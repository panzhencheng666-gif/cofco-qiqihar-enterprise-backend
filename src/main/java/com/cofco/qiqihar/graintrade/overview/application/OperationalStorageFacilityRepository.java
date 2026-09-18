package com.cofco.qiqihar.graintrade.overview.application;

import java.time.LocalDate;
import java.util.List;

public interface OperationalStorageFacilityRepository {
    List<OperationalFacilityCatalogue.StorageFacility> find(String regionCode, String productCode, LocalDate asOf);
    String latestSourceAsOf(String regionCode);
}
