package com.cofco.qiqihar.graintrade.masterdata.importing;

import java.util.List;

/** Narrow master-data contract exposed to the import module. */
public interface RegionImportDirectory {

    List<RegionEntry> regions();

    record RegionEntry(String code, String name, String parentCode) {
    }
}
