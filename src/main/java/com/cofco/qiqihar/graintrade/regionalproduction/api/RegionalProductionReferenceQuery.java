package com.cofco.qiqihar.graintrade.regionalproduction.api;

import java.util.Optional;

/** Read-only reference contract exposed to other business modules. */
public interface RegionalProductionReferenceQuery {
    Optional<RegionReference> findRegion(String regionCode);

    boolean supportsProduct(String productCode);

    record RegionReference(
            String code, String name, String parentCode, String administrativeLevel) {}
}
